package eu.kanade.tachiyomi.animeextension.en.reanime

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.reanime.FlixProxyServer.Companion.decApi
import eu.kanade.tachiyomi.animeextension.en.reanime.FlixProxyServer.Companion.flixCloudUrl
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class ReAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Re:ANIME (𝕬𝕽)"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val apiUrl: String
        get() = "$baseUrl/api/v1"

    private val flixUrl by lazy { "$baseUrl/api/flix" }

    private val json: Json by injectLazy()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val preferredLang: String
        get() = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val hideFiller: Boolean
        get() = preferences.getBoolean(PREF_HIDE_FILLER_KEY, PREF_HIDE_FILLER_DEFAULT)

    private fun apiHeaders(referer: String = "$baseUrl/home"): Headers = headers.newBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", referer)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    override val client: OkHttpClient by lazy { network.client }

    private data class AnimeMeta(val anilistId: Int, val subbed: Int, val dubbed: Int)

    private val animeMetaCache by lazy { LruCache<String, AnimeMeta>(64) }

    @Synchronized
    private fun fetchAnimeMeta(animeSlug: String): AnimeMeta? {
        animeMetaCache.get(animeSlug)?.let { return it }
        return try {
            client.newCall(
                GET("$detailsFromApiUrl/$animeSlug", apiHeaders("$detailsUrl/$animeSlug")),
            ).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val dto = jsonParser.decodeFromString<AnimeDetailDto>(res.body.string())
                AnimeMeta(
                    anilistId = dto.anilistId ?: 0,
                    subbed = dto.subbed ?: 0,
                    dubbed = dto.dubbed ?: 0,
                ).also { animeMetaCache.put(animeSlug, it) }
            }
        } catch (_: Exception) { null }
    }

    private var nextLatestCursor: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 36
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "popularity_desc")
            addQueryParameter("limit", "36")
            addQueryParameter("offset", offset.toString())
        }.build()
        return GET(url, apiHeaders("$baseUrl/search"))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val dto = jsonParser.decodeFromString<SearchResponseDto>(response.body.string())
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime(titleLanguage) }
        val hasNextPage = ((dto.offset ?: 0) + (dto.limit ?: 0)) < (dto.total ?: 0)
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) nextLatestCursor = null
        val urlBuilder = "$apiUrl/home/latest-aired".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "12")
            addQueryParameter("lang", preferredLang)
            if (page > 1 && nextLatestCursor != null) {
                addQueryParameter("cursor", nextLatestCursor!!)
            }
        }
        return GET(urlBuilder.build(), apiHeaders("$baseUrl/home"))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val dto = jsonParser.decodeFromString<LatestDto>(response.body.string())
        nextLatestCursor = dto.nextCursor
        val animes = dto.data.mapNotNull { it.toSAnime(titleLanguage) }
        return AnimesPage(animes, dto.hasMore)
    }

    // =============================== Search ===============================
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    @RequiresApi(Build.VERSION_CODES.O)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            val limit = 36
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", ((page - 1) * limit).toString())
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.forEach { filter ->
                when (filter) {
                    is Filters.SortFilter -> addQueryParameter("sort", filter.getValue())
                    is Filters.FormatFilter -> filter.getValue()?.let { addQueryParameter("format", it) }
                    is Filters.StatusFilter -> filter.getValue()?.let { addQueryParameter("status", it) }
                    is Filters.SeasonFilter -> filter.getValue()?.let { addQueryParameter("season", it) }
                    is Filters.OriginFilter -> filter.getValue()?.let { addQueryParameter("country", it) }
                    is Filters.YearFilter -> filter.getValue()?.let { addQueryParameter("year", it) }
                    is Filters.GenreFilter -> {
                        val genres = filter.getSelectedValues()
                        if (genres.isNotEmpty()) addQueryParameter("genre", genres)
                    }
                    is Filters.CharacterFilter -> {
                        val characters = filter.getSelectedValues()
                        if (characters.isNotEmpty()) addQueryParameter("character", characters)
                    }
                    is Filters.StaffFilter -> {
                        val staff = filter.getSelectedValues()
                        if (staff.isNotEmpty()) addQueryParameter("staff", staff)
                    }
                    is Filters.StudioFilter -> {
                        val studios = filter.getSelectedValues()
                        if (studios.isNotEmpty()) addQueryParameter("studio", studios)
                    }
                    is Filters.TagFilter -> {
                        val tags = filter.getSelectedValues()
                        if (tags.isNotEmpty()) addQueryParameter("tag", tags)
                    }
                    else -> {}
                }
            }
        }.build()
        return GET(url, apiHeaders("$baseUrl/search"))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val dto = jsonParser.decodeFromString<SearchResponseDto>(response.body.string())
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime(titleLanguage) }
        val hasNextPage = ((dto.offset ?: 0) + (dto.limit ?: 0)) < (dto.total ?: 0)
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    private val detailsUrl = "$baseUrl/anime"
    private val detailsFromApiUrl = "$apiUrl/anime"

    override fun getAnimeUrl(anime: SAnime): String = "$detailsUrl/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$detailsFromApiUrl/${anime.url}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = jsonParser.decodeFromString<AnimeDetailDto>(response.body.string())
        if (dto.anilistId != null && dto.anilistId > 0) {
            animeMetaCache.put(dto.animeId, AnimeMeta(dto.anilistId, dto.subbed ?: 0, dto.dubbed ?: 0))
        }
        return dto.toSAnime(titleLanguage).apply {
            description = buildDescription(dto)
        }
    }

    private fun getOrdinal(n: Int): String = if (n in 11..13) {
        "${n}th"
    } else {
        when (n % 10) {
            1 -> "${n}st"; 2 -> "${n}nd"; 3 -> "${n}rd"; else -> "${n}th"
        }
    }

    private fun formatFuzzyDate(date: FuzzyDateDto?): String? {
        if (date == null || date.year == null || date.year <= 0) return null
        if (date.month == null || date.month !in 1..12) return null
        val monthStr = MONTHS[date.month - 1]
        return if (date.day != null && date.day > 0) {
            "$monthStr ${getOrdinal(date.day)}, ${date.year}"
        } else {
            "$monthStr ${date.year}"
        }
    }

    private fun parseAiringDate(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val parts = iso.substringBefore('T').split('-')
            formatFuzzyDate(FuzzyDateDto(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()))
        } catch (_: Exception) { null }
    }

    private fun buildDescription(dto: AnimeDetailDto): String = buildString {
        dto.averageScore?.let { score ->
            getFancyScore(score).takeIf { it.isNotEmpty() }?.let { append(it); append("\n\n") }
        }
        dto.description?.let { raw ->
            val cleaned = raw.replace(BR_REGEX, "\n").replace(HTML_TAG_REGEX, "").trim()
            if (cleaned.isNotBlank()) { append(cleaned); append("\n\n") }
        }
        val altTitles = mutableListOf<String>()
        dto.title?.let { title ->
            val preferred = title.preferredTitle(titleLanguage)
            listOfNotNull(title.english, title.romaji, title.native)
                .filter { it.isNotBlank() && it != preferred }
                .forEach { altTitles.add(it) }
        }
        dto.synonyms?.filter { it.isNotBlank() }?.let { altTitles.addAll(it) }
        val uniqueAltTitles = altTitles.distinctBy { it }
        val infoLines = mutableListOf<String>()
        if (uniqueAltTitles.isNotEmpty()) infoLines.add("**Alternative Titles**: ${uniqueAltTitles.joinToString(" • ")}")
        dto.format?.takeIf { it.isNotBlank() }?.let { infoLines.add("**Format**: $it") }
        val sourceStr = dto.source?.takeIf { it.isNotBlank() }?.let { it.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } }
        val countryStr = dto.countryOfOrigin?.takeIf { it.isNotBlank() }
        if (sourceStr != null || countryStr != null) infoLines.add("**Source**: " + listOfNotNull(sourceStr, countryStr).joinToString(" • "))
        val startDateStr = formatFuzzyDate(dto.startDate)
        val endDateStr = formatFuzzyDate(dto.endDate)
        when {
            startDateStr != null && endDateStr != null -> {
                if (startDateStr == endDateStr) {
                    infoLines.add("**Air Date**: On $startDateStr")
                } else infoLines.add("**Airing**: From $startDateStr to $endDateStr")
            }
            startDateStr != null -> infoLines.add("**Start Date**: $startDateStr")
            endDateStr != null -> infoLines.add("**End Date**: $endDateStr")
        }
        dto.nextAiringEpisode?.let { next ->
            val epNum = next.episode
            val airingAt = next.airingAt?.takeIf { it.isNotBlank() }
            if (epNum != null && airingAt != null) {
                parseAiringDate(airingAt)?.let { infoLines.add("**Next Airing**: Episode $epNum on $it") }
            }
        }
        val seasonStr = dto.season?.takeIf { it.isNotBlank() && it != "0" }?.replaceFirstChar { c -> c.titlecase() }
        val seasonYearStr = dto.seasonYear?.takeIf { it > 0 }?.toString()
        if (seasonStr != null && seasonYearStr != null) infoLines.add("**Season**: $seasonStr $seasonYearStr")
        dto.duration?.takeIf { it > 0 }?.let { infoLines.add("**Duration**: ${it}m") }
        dto.rating?.takeIf { it.isNotBlank() }?.let { infoLines.add("**Rating**: $it") }
        if (infoLines.isNotEmpty()) { append(infoLines.joinToString("\n")); append("\n") }
        val trackers = buildList {
            dto.anilistId?.takeIf { it > 0 }?.let { add("[AniList](https://anilist.co/anime/$it)") }
            dto.malId?.takeIf { it > 0 }?.let { add("[MAL](https://myanimelist.net/anime/$it)") }
            dto.kitsuId?.takeIf { it > 0 }?.let { add("[Kitsu](https://kitsu.io/anime/$it)") }
            dto.anidbId?.takeIf { it > 0 }?.let { add("[AniDB](https://anidb.net/anime/$it)") }
            dto.animePlanetId?.takeIf { it.isNotBlank() }?.let { add("[Anime-Planet](https://www.anime-planet.com/anime/$it)") }
            dto.animeNewsNetworkId?.takeIf { it > 0 }?.let { add("[ANN](https://www.animenewsnetwork.com/encyclopedia/anime.php?id=$it)") }
            dto.anisearchId?.takeIf { it > 0 }?.let { add("[Anisearch](https://www.anisearch.com/anime/$it)") }
            dto.simklId?.takeIf { it > 0 }?.let { add("[Simkl](https://simkl.com/anime/$it)") }
            dto.tmdbId?.takeIf { it > 0 }?.let { add("[TMDB](https://www.themoviedb.org/tv/$it)") }
            dto.tvdbId?.takeIf { it > 0 }?.let { add("[TVDB](https://thetvdb.com/series/$it)") }
            dto.imdbId?.takeIf { it.isNotBlank() }?.let { add("[IMDB](https://www.imdb.com/title/$it)") }
        }
        if (trackers.isNotEmpty()) { append("**Trackers**: ${trackers.joinToString(" • ")}"); append("\n") }
        dto.externalLinks?.filter { it.type == "STREAMING" }?.takeIf { it.isNotEmpty() }?.let { links ->
            val streamingLinks = links.mapNotNull { link -> link.site?.let { s -> link.url?.let { u -> "[$s]($u)" } } }
            if (streamingLinks.isNotEmpty()) { append("**Streaming**: ${streamingLinks.joinToString(" • ")}"); append("\n") }
        }
        dto.trailer?.takeIf { it.site == "youtube" && !it.id.isNullOrBlank() }?.let {
            append("**Trailer**: [YouTube](https://www.youtube.com/watch?v=${it.id})"); append("\n")
        }
        dto.bannerImage?.takeIf { it.isNotBlank() }?.let { append("\n![Banner]($it)") }
    }

    private fun getFancyScore(score: Int): String {
        if (score <= 0) return ""
        val stars = (score / 20.0).roundToInt().coerceIn(1, 5)
        return "${"★".repeat(stars)}${"☆".repeat(5 - stars)} $score"
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = "$detailsFromApiUrl/${anime.url}/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "2000").build()
        return GET(url, apiHeaders("$detailsUrl/${anime.url}"))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (!response.isSuccessful) throw Exception("Failed to load episodes (HTTP ${response.code})")
        val dto = try { jsonParser.decodeFromString<EpisodeListDto>(response.body.string()) } catch (_: Exception) { throw Exception("Could not parse episode list. The anime may not have episodes yet.") }
        val visibleEpisodes = (dto.data ?: emptyList()).filterNot { (it.isFiller == true) && hideFiller }
        if (visibleEpisodes.isEmpty()) throw Exception("No episodes available for this anime yet. It may not have aired.")
        val segments = response.request.url.pathSegments
        val animeIdx = segments.indexOf("anime")
        val animeSlug = if (animeIdx != -1 && animeIdx + 1 < segments.size) segments[animeIdx + 1] else ""
        val meta = animeMetaCache.get(animeSlug) ?: fetchAnimeMeta(animeSlug)
        val maxSub = meta?.subbed ?: 0
        val maxDub = meta?.dubbed ?: 0
        return visibleEpisodes.map { ep ->
            SEpisode.create().apply {
                val epNum = ep.episodeNumber
                episode_number = epNum.toFloat()
                val safeEpisodeId = ep.episodeId ?: "ep-${epNum.toInt()}"
                url = "$animeSlug/$safeEpisodeId"
                val epNumStr = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()
                val epTitle = ep.getPreferredTitle(titleLanguage)
                val baseName = if (epTitle.isNotBlank() && !epTitle.contains("Episode", ignoreCase = true)) {
                    "Episode $epNumStr - $epTitle"
                } else {
                    "Episode $epNumStr"
                }
                name = buildString {
                    append(baseName)
                    if (ep.isRecap) append(" [Recap]")
                    if (ep.isFiller && !hideFiller) append(" [Filler]")
                }
                val hasSub = epNum <= maxSub
                val hasDub = epNum <= maxDub
                scanlator = when {
                    hasSub && hasDub -> "Sub & Dub"
                    hasSub -> "Sub"
                    hasDub -> "Dub"
                    else -> null
                }
                date_upload = try { dateFormat.parse(ep.aired ?: "")?.time ?: 0L } catch (_: Exception) { 0L }
                // Set the episode preview/thumbnail via reflection — lib v14's SEpisode
                // has no preview_url property, but AniZen's runtime (v16+) does
                // (setPreview_url on SEpisodeImpl). The API's per-episode thumbnail
                // image is what the app renders for episode thumbnails/previews.
                ep.thumbnail?.takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "preview_url", it)
                }
                // The API exposes a per-episode synopsis (often empty for older
                // seasons); surface it when present.
                ep.description?.takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "summary", it)
                }
            }
        }.reversed()
    }

    // ============================== Video Streams ==============================
    // The original Keiyoushi extension uses getHosterList/getVideoList (AniZen
    // Hoster API). This adaptation folds both into videoListRequest/videoListParse
    // for lib v14 compatibility. The request itself fetches the episode page HTML
    // (needed for AniZen's Referer chain); videoListParse extracts the episode
    // slug from the URL and does the real video server lookup.

    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url is "slug/ep-N" — build the watch page URL
        val (slug, epId) = episode.url.split("/", limit = 2)
            .let { it.getOrNull(0) to (it.getOrNull(1) ?: "") }
        val epNumber = epId.removePrefix("ep-")
        val watchUrl = "$baseUrl/watch/$slug?ep=$epNumber"
        return GET(watchUrl, apiHeaders(watchUrl))
    }

    override fun videoListParse(response: Response): List<Video> {
        // Extract slug and ep number from the watch URL we requested
        val requestUrl = response.request.url.toString()
        val slug = requestUrl.substringAfter("/watch/").substringBefore("?")
        val epNumber = requestUrl.substringAfter("ep=").substringBefore("&").ifBlank { "1" }

        val meta = animeMetaCache.get(slug) ?: fetchAnimeMeta(slug)
        if (meta == null || meta.anilistId <= 0) return emptyList()

        val referer = "$baseUrl/watch/$slug?ep=$epNumber"
        val flixRes = client.newCall(
            GET("$flixUrl/${meta.anilistId}/$epNumber", apiHeaders(referer)),
        ).execute()

        val parsed = flixRes.use {
            if (!it.isSuccessful) return emptyList()
            jsonParser.decodeFromString<VideoResponseDto>(it.body.string())
        }
        if (parsed.success != true || parsed.servers.isNullOrEmpty()) return emptyList()

        // Dedup by dataLink + dataType so both sub and dub servers survive.
        // The API returns separate server entries for sub/dub with the same
        // dataLink — deduping by dataLink alone would silently drop dub.
        val targetServers = parsed.servers.distinctBy { "${it.dataLink}-${it.dataType}" }

        // Apply preferredServer filter if set (e.g. only use HD-1 servers)
        val serverPref = preferredServer.takeIf { it.isNotBlank() }
        val filteredServers = if (serverPref != null) {
            val matched = targetServers.filter { it.serverName?.equals(serverPref, true) == true }
            if (matched.isNotEmpty()) matched else targetServers
        } else {
            targetServers
        }

        val videos = filteredServers.mapNotNull { server ->
            val dataLink = server.dataLink ?: return@mapNotNull null
            val audioType = server.dataType ?: "sub"
            runCatching { extractFromServer(dataLink, audioType) }.getOrDefault(emptyList())
        }.flatten()

        return videos
    }

    private fun decryptFlixCloudStream(dataLink: String, audioType: String): Pair<String, List<Track>>? {
        val flixHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", flixCloudUrl)
            .add("Referer", "$flixCloudUrl/")
            .build()
        val decHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .build()

        // Step 1: Fetch embed page; extract XOR key
        val html = client.newCall(GET(dataLink, flixHeaders)).execute().use { it.body.string() }
        val hardcodedFallback = listOf(
            157, 42, 241, 71, 179, 142, 92, 112,
            166, 25, 228, 59, 216, 98, 15, 197,
        ).map { it.toByte() }.toByteArray()
        var xorMask: ByteArray? = null
        val scriptPath = HLS_SCRIPT_REGEX.find(html)?.groupValues?.get(1)
        if (scriptPath != null) {
            val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else "$flixCloudUrl$scriptPath"
            try {
                val jsContent = client.newCall(GET(scriptUrl, flixHeaders)).execute().use { it.body.string() }
                xorMask = XOR_MASK_REGEX.find(jsContent)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim().toInt().toByte() }?.toByteArray()
            } catch (_: Exception) {}
        }
        if (xorMask != null) {
            saveXorMask(xorMask)
        } else xorMask = loadSavedXorMask() ?: hardcodedFallback

        val server = getProxyServer(headers, xorMask)
        val dataMatch = EMBED_DATA_REGEX.find(html) ?: return null
        val rawJson = json5ToJson(dataMatch.groupValues[1])

        val embedDataDto = try { jsonParser.decodeFromString<FlixcloudEmbedDataDto>(rawJson) } catch (_: Exception) { FlixcloudEmbedDataDto() }
        val subtitleTracks = embedDataDto.subtitles?.mapNotNull { sub ->
            val subUrl = sub.url ?: return@mapNotNull null
            Track(server.createSubtitleUrl(subUrl), sub.language ?: "Unknown")
        } ?: emptyList()

        // Strip subtitles for enc-dec
        val embedData = try {
            val obj = jsonParser.parseToJsonElement(rawJson).jsonObject.toMutableMap()
            obj.remove("subtitles")
            JsonObject(obj).toString()
        } catch (_: Exception) { rawJson }

        // Step 2: Get Token
        val tokenPayload = """{"data":$embedData}"""
        val tokenDto = client.newCall(
            Request.Builder()
                .url("$decApi/dec-flixcloud?type=token")
                .post(tokenPayload.toRequestBody("application/json".toMediaType()))
                .headers(decHeaders).build(),
        ).execute().use { jsonParser.decodeFromString<DecFlixCloudTokenResponseDto>(it.body.string()) }
        if (tokenDto.status != 200 || tokenDto.result == null) return null

        // Step 3: Fetch encrypted stream
        val m3u8Body = client.newCall(
            GET("$flixCloudUrl/api/m3u8/${tokenDto.result.token}", flixHeaders),
        ).execute().use { it.body.string() }
        val m3u8JsonElement = try { jsonParser.parseToJsonElement(m3u8Body) } catch (_: Exception) { return null }

        // Step 4: Decrypt Stream
        val streamPayload = buildJsonObject {
            putJsonObject("data") {
                put("context", tokenDto.result.context)
                put("stream_response", m3u8JsonElement.jsonObject)
            }
        }.toString()
        val streamDto = client.newCall(
            Request.Builder()
                .url("$decApi/dec-flixcloud?type=stream")
                .post(streamPayload.toRequestBody("application/json".toMediaType()))
                .headers(decHeaders).build(),
        ).execute().use { jsonParser.decodeFromString<DecFlixCloudStreamResponseDto>(it.body.string()) }
        if (streamDto.status != 200 || streamDto.result == null) return null

        val streamUrl = streamDto.result.stream
        val wPayload = streamDto.result.context["w_payload"]?.jsonPrimitive?.content ?: return null

        // Step 5: Build local proxy URL
        val localManifestUrl = server.createProxyUrl(streamUrl, wPayload)
        return localManifestUrl to subtitleTracks
    }

    private fun extractFromServer(dataLink: String, audioType: String): List<Video> {
        return try {
            val (localManifestUrl, subtitleTracks) = decryptFlixCloudStream(dataLink, audioType)
                ?: return emptyList()
            val audioLabel = if (audioType.equals("dub", true)) "Dub" else "Sub"
            val masterText = client.newCall(GET(localManifestUrl, headers)).execute().use { it.body.string() }
            val variants = STREAM_INF_REGEX.findAll(masterText).mapNotNull { match ->
                val attributes = match.groupValues[1]
                match.groupValues[2].trim().takeIf { it.isNotBlank() && !it.startsWith("#") } ?: return@mapNotNull null
                val label = attributes.hlsAttr("RESOLUTION")?.substringAfter('x', "")?.let { "${it}p" }
                    ?: attributes.hlsAttr("BANDWIDTH")?.toLongOrNull()?.let { "${it / 1000} kbps" } ?: "Auto"
                Video(
                    url = localManifestUrl,
                    quality = "$label $audioLabel",
                    videoUrl = localManifestUrl,
                    headers = headers,
                    subtitleTracks = subtitleTracks,
                )
            }.toList()
            variants.ifEmpty { listOf(Video(localManifestUrl, "Auto $audioLabel", localManifestUrl, headers = headers, subtitleTracks = subtitleTracks)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun String.hlsAttr(name: String): String? =
        Regex("""(?:^|[,:])$name=(?:"([^"]*)"|([^,"]*))""").find(this)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.takeIf { it.isNotBlank() }

    private fun json5ToJson(json5: String): String = json5
        .replace(JSON5_KEY_REGEX) { "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}" }
        .replace(JSON5_TRAILING_COMMA_REGEX, "$1")
        .replace(JSON5_UNDEFINED_REGEX, ": null")

    private fun EpisodeDto.getPreferredTitle(language: String): String {
        val preferred = when (language) {
            "native" -> titleJapanese
            "romaji" -> titleRomanji
            else -> title
        }?.takeIf { it.isNotBlank() }
        return preferred ?: title.takeIf { it.isNotBlank() }
            ?: titleRomanji?.takeIf { it.isNotBlank() }
            ?: titleJapanese?.takeIf { it.isNotBlank() } ?: ""
    }

    @Volatile
    private var proxyServer: FlixProxyServer? = null

    @Synchronized
    private fun getProxyServer(headers: Headers, segmentMask: ByteArray): FlixProxyServer {
        if (proxyServer == null || !proxyServer!!.isAlive) {
            proxyServer?.stop()
            proxyServer = FlixProxyServer(headers, segmentMask)
            proxyServer!!.start()
        } else {
            proxyServer!!.updateSegmentMask(segmentMask)
        }
        return proxyServer!!
    }

    private fun loadSavedXorMask(): ByteArray? {
        val savedStr = preferences.getString("flixcloud_xor_mask", null) ?: return null
        return try { savedStr.split(",").map { it.trim().toInt().toByte() }.toByteArray() } catch (_: Exception) { null }
    }

    private fun saveXorMask(mask: ByteArray) {
        val maskStr = mask.joinToString(",") { (it.toInt() and 0xFF).toString() }
        preferences.edit().putString("flixcloud_xor_mask", maskStr).apply()
    }

    private fun sortVideos(videos: List<Video>): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val audio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val audioLabel = if (audio == "dub") "Dub" else "Sub"
        val qualityOrder = listOf("1080p", "720p", "480p", "360p")
        return videos.sortedWith(
            // Sort by audio type first (quality strings now include "Sub"/"Dub" label)
            compareByDescending<Video> { it.quality.contains(audioLabel, ignoreCase = true) }
                // Then by preferred quality resolution
                .thenByDescending { it.quality.contains(quality, ignoreCase = true) }
                // Then by resolution order (1080p first)
                .thenBy { video ->
                    val index = qualityOrder.indexOfFirst { video.quality.contains(it) }
                    if (index == -1) qualityOrder.size else index
                },
        )
    }

    override fun List<Video>.sort(): List<Video> = sortVideos(this)

    /**
     * Set a field on SEpisode via reflection. lib v14's SEpisode doesn't have
     * thumbnail_url/preview_url setters, but AniZen's runtime (v16) does.
     * Silently no-ops if the setter doesn't exist.
     */
    private fun setEpisodeField(episode: SEpisode, fieldName: String, value: String) {
        try {
            val setter = episode.javaClass.getMethod(
                "set${fieldName.replaceFirstChar { it.uppercase() }}",
                String::class.java,
            )
            setter.invoke(episode, value)
        } catch (_: NoSuchMethodException) {
        } catch (_: Exception) {
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY; title = "Preferred Domain"
            entries = PREF_DOMAIN_ENTRIES.toTypedArray()
            entryValues = PREF_DOMAIN_VALUES.toTypedArray()
            setDefaultValue(PREF_DOMAIN_DEFAULT); summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY; title = "Preferred Title Language"
            entries = PREF_TITLE_LANG_ENTRIES.toTypedArray()
            entryValues = PREF_TITLE_LANG_VALUES.toTypedArray()
            setDefaultValue(PREF_TITLE_LANG_DEFAULT); summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY; title = "Preferred Type For Latest"
            entries = PREF_LANG_ENTRIES.toTypedArray()
            entryValues = PREF_LANG_VALUES.toTypedArray()
            setDefaultValue(PREF_LANG_DEFAULT); summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY; title = "Preferred Server"
            entries = PREF_SERVER_ENTRIES.toTypedArray()
            entryValues = PREF_SERVER_VALUES.toTypedArray()
            setDefaultValue(PREF_SERVER_DEFAULT); summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY; title = "Preferred Audio Type"
            entries = PREF_AUDIO_ENTRIES.toTypedArray()
            entryValues = PREF_AUDIO_VALUES.toTypedArray()
            setDefaultValue(PREF_AUDIO_DEFAULT); summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY; title = "Preferred Quality"
            entries = PREF_QUALITY_ENTRIES.toTypedArray()
            entryValues = PREF_QUALITY_VALUES.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT); summary = "%s"
        }.also(screen::addPreference)
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_FILLER_KEY; title = "Hide Filler Episodes"
            summary = "Hides episodes marked as filler from the episode list."
            setDefaultValue(PREF_HIDE_FILLER_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf("reanime.to", "reanime.cz")
        private val PREF_DOMAIN_VALUES = listOf("https://reanime.to", "https://reanime.cz")
        private const val PREF_DOMAIN_DEFAULT = "https://reanime.to"
        private const val PREF_LANG_KEY = "preferred_lang"
        private val PREF_LANG_ENTRIES = listOf("All", "Sub", "Dub")
        private val PREF_LANG_VALUES = listOf("", "sub", "dub")
        private const val PREF_LANG_DEFAULT = ""
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private val PREF_AUDIO_ENTRIES = listOf("Sub", "Dub")
        private val PREF_AUDIO_VALUES = listOf("sub", "dub")
        private const val PREF_AUDIO_DEFAULT = "sub"
        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_ENTRIES = listOf("HD-1", "HD-2")
        private val PREF_SERVER_VALUES = listOf("HD-1", "HD-2")
        private const val PREF_SERVER_DEFAULT = "HD-1"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English", "Japanese (Native)")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english", "native")
        private const val PREF_HIDE_FILLER_KEY = "hide_filler"
        private const val PREF_HIDE_FILLER_DEFAULT = false
        private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE)
        private val EMBED_DATA_REGEX = Regex("""type:\s*"data",\s*data:\s*(\{.*?\}),?\s*uses:""", RegexOption.DOT_MATCHES_ALL)
        private val JSON5_KEY_REGEX = Regex("""([{,]\s*)([\w_]+)(\s*:)""")
        private val JSON5_TRAILING_COMMA_REGEX = Regex(""",\s*([}\]])""")
        private val JSON5_UNDEFINED_REGEX = Regex(""":\s*undefined\b""")
        private val HLS_SCRIPT_REGEX = Regex("""href="([^"]*hls\.js[^"]*)"""")
        private val XOR_MASK_REGEX = Regex("""for\(var f=\[(\d{1,3}(?:,\d{1,3}){15})]""")
        private val STREAM_INF_REGEX = Regex("""#EXT-X-STREAM-INF:(.+)\r?\n(.+)""")

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING", "Releasing" -> SAnime.ONGOING
            "FINISHED", "Finished" -> SAnime.COMPLETED
            "CANCELLED", "Cancelled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }
}
