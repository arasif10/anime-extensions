package eu.kanade.tachiyomi.animeextension.all.desidubanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class DesiDubAnime : AnimeHttpSource() {

    override val name = "DesiDubAnime"

    override val baseUrl = "https://www.desidubanime.me"

    // The site hosts Hindi/Tamil/Telugu/English dubs of anime and cartoons.
    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("desidubanime", "all", 1)) so the app maps the
    // index entry to the installed extension across version bumps. The exact
    // formula lives in the companion object.
    override val id: Long = generateId("desidubanime", "all", 1)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ============================== Catalogue ==============================
    // Kiranime theme: every catalogue (popular/latest/search/filters) is one
    // admin-ajax "advanced_search" call. It REQUIRES the search_actions nonce
    // (from any page's inline kiraConfig) plus an X-Requested-With header,
    // otherwise WordPress kills the request. The response is
    // {"data":{"html":"<article.anime-card>...","max_pages":N,"current_page":P}}.
    private var cachedNonce: String? = null

    private fun searchNonce(): String {
        cachedNonce?.let { return it }
        val document = client.newCall(GET("$baseUrl/search/", headers)).execute().use {
            Jsoup.parse(it.body.string(), baseUrl)
        }
        val nonce = NONCE_REGEX.find(document.data())?.groupValues?.get(1).orEmpty()
        cachedNonce = nonce
        return nonce
    }

    private fun ajaxSearch(
        page: Int,
        keyword: String,
        orderby: String,
        order: String,
        filters: List<Pair<String, String>>,
    ): Request {
        val form = FormBody.Builder()
            .add("action", "advanced_search")
            .add("nonce", searchNonce())
            .add("page", page.toString())
            .add("s_keyword", keyword)
            .add("orderby", orderby)
            .add("order", order)
        filters.forEach { (fieldName, value) -> form.add(fieldName, value) }
        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/search/")
            .build()
        return POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, form.build())
    }

    override fun popularAnimeRequest(page: Int): Request =
        ajaxSearch(page, "", "popular", "DESC", emptyList())

    override fun latestUpdatesRequest(page: Int): Request =
        ajaxSearch(page, "", "updated", "DESC", emptyList())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = buildList {
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> addAll(filter.toParams())
                    is StatusFilter -> addAll(filter.toParams())
                    is TypeFilter -> addAll(filter.toParams())
                    is SeasonFilter -> addAll(filter.toParams())
                    is YearFromFilter -> addAll(filter.toParams())
                    is YearToFilter -> addAll(filter.toParams())
                    else -> {}
                }
            }
        }
        return ajaxSearch(page, query, sortParam(filters), orderParam(filters), params)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAjaxPage(response)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseAjaxPage(response)
    override fun searchAnimeParse(response: Response): AnimesPage = parseAjaxPage(response)

    private fun parseAjaxPage(response: Response): AnimesPage {
        val json = JSONObject(response.body.string())
        val data = json.optJSONObject("data") ?: return AnimesPage(emptyList(), false)
        val document = Jsoup.parse(data.optString("html"), baseUrl)
        val animes = document.select("article.anime-card").mapNotNull(::cardToAnime)
        val hasNext = data.optInt("current_page", 1) < data.optInt("max_pages", 0)
        return AnimesPage(animes, hasNext)
    }

    private fun cardToAnime(card: Element): SAnime? {
        // The card's links point at /watch/<slug>-episode-N/ pages; the series
        // URL only appears in the "Info" button's onclick handler.
        val onclick = card.selectFirst("button[onclick*='/anime/']")?.attr("onclick").orEmpty()
        val seriesUrl = ANIME_URL_REGEX.find(onclick)?.value ?: return null
        return SAnime.create().apply {
            title = card.selectFirst("h3 a")?.text()
                ?: card.selectFirst("a.stretched-link")?.attr("title")
                ?: return@apply
            url = seriesUrl
            thumbnail_url = card.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
        }
    }

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return SAnime.create().apply {
            // The h1 concatenates both language spans ("Demon Slayer Season 2"
            // + "Kimetsu no Yaiba: Yuukaku-hen Season 2"), so the clean title
            // comes from og:title instead.
            title = document.ogTitle().ifBlank { document.selectFirst("h1")?.text().orEmpty() }
            // og:image is the site's logo banner; the real poster is
            // img.anime-main-image (a MAL CDN image).
            thumbnail_url = document.selectFirst("img.anime-main-image")?.attr("abs:src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("abs:content")
            description = document.getElementsByAttributeValue("aria-label", "Anime Overview")
                .firstOrNull()?.wholeText()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            // Only the info <dl> (dt/dd rows) may be used for metadata - the
            // nav mega-menu also contains 60+ /genre/ links that would
            // otherwise pollute the genre list.
            author = document.infoValue("Studios")?.text()?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("N/A", true) }
            genre = document.infoValue("Genres")?.select("a")
                ?.mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
                ?.distinct()
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
            status = document.statusFromAired()
        }
    }

    /** Clean page title from og:title, minus the site-name suffix. */
    private fun Document.ogTitle(): String = selectFirst("meta[property=og:title]")
        ?.attr("content")
        ?.removeSuffix(" - Desi Dub Anime")
        .orEmpty()

    /** Value <dd> of an info row by its <dt> label ("Studios", "Genres", ...). */
    private fun Document.infoValue(label: String): Element? =
        select("dt").firstOrNull { it.text().equals(label, ignoreCase = true) }
            ?.nextElementSibling()

    /**
     * Status is never rendered as text on series pages (the old
     * bodyText.contains("Completed") probe could never match), but the "Aired"
     * row always is: "Dec 5, 2021 to Feb 13, 2022" with a past end date means
     * completed, an unparsable or future end means ongoing, no row at all
     * means unknown.
     */
    private fun Document.statusFromAired(): Int {
        val aired = infoValue("Aired")?.text().orEmpty()
        if (aired.isBlank()) return SAnime.UNKNOWN
        val endRaw = if (aired.contains(" to ", ignoreCase = true)) {
            aired.substringAfterLast("to ").trim()
        } else {
            aired
        }
        val end = runCatching { SimpleDateFormat("MMM d, yyyy", Locale.US).parse(endRaw) }
            .getOrNull() ?: return SAnime.ONGOING
        return if (end.before(Date())) SAnime.COMPLETED else SAnime.ONGOING
    }

    // ============================== Episodes ==============================
    // Episodes live behind a per-season ajax: GET admin-ajax.php
    // ?action=get_episodes&anime_id=<seasonId>&page=N&order=desc (no nonce).
    // Season ids are the data-season attributes of the series page; each
    // episode object carries url, number, title, meta_number and released.
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val seasons = document.select("button[data-season]")
            .map { it.attr("data-season") to it.text().trim() }
            .filter { it.first.isNotBlank() }
            .ifEmpty {
                document.select("#seasonContent[data-season]")
                    .map { it.attr("data-season") to "Season 1" }
            }
        if (seasons.isEmpty()) return emptyList()
        val multiSeason = seasons.size > 1

        val episodes = mutableListOf<SEpisode>()
        for ((seasonId, seasonLabel) in seasons) {
            var page = 1
            while (true) {
                val request = GET(
                    "$baseUrl/wp-admin/admin-ajax.php?action=get_episodes&anime_id=$seasonId&page=$page&order=desc",
                    ajaxHeaders(),
                )
                val data = runCatching {
                    JSONObject(client.newCall(request).execute().use { it.body.string() })
                        .optJSONObject("data")
                }.getOrNull() ?: break
                val array = data.optJSONArray("episodes") ?: break
                if (array.length() == 0) break
                for (i in 0 until array.length()) {
                    val ep = array.optJSONObject(i) ?: continue
                    val epUrl = ep.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val number = ep.optString("meta_number").ifBlank {
                        ep.optString("number").substringAfter("Episode").trim()
                    }
                    val label = ep.optString("number").ifBlank { "Episode $number" }
                    val epTitle = ep.optString("title")
                    episodes += SEpisode.create().apply {
                        url = epUrl
                        // "Episode 5 - The Frontier Lord ..."; a season prefix is
                        // only added when the series actually has several seasons
                        // so that repeated episode numbers stay distinguishable.
                        name = buildString {
                            if (multiSeason) append(seasonPrefix(seasonLabel)).append(' ')
                            append(label)
                            if (epTitle.isNotBlank() && !label.contains(epTitle)) {
                                append(" - ").append(epTitle)
                            }
                        }
                        episode_number = number.toFloatOrNull() ?: (i + 1).toFloat()
                        scanlator = if (multiSeason) seasonLabel else null
                        date_upload = parseDate(ep.optString("released"))
                        // Episode still from the site's TMDB-backed payload -
                        // AniZen's runtime renders it as the row thumbnail via
                        // preview_url (absent from the lib-14 stub this
                        // compiles against, hence the reflection helper).
                        ep.optString("thumbnail").takeIf { it.isNotBlank() }?.let {
                            setEpisodeField(this, "preview_url", it)
                        }
                    }
                }
                if (page >= data.optInt("max_episodes_page", 1)) break
                page++
            }
        }
        return episodes
    }

    private fun ajaxHeaders(): Headers = headers.newBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Referer", "$baseUrl/")
        .build()

    private fun seasonPrefix(label: String): String =
        Regex("\\d+").find(label)?.value?.let { "S$it" } ?: label

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw.trim())!!.time
        }.getOrDefault(0L)
    }

    /**
     * Sets a field on SEpisode that exists in AniZen's runtime (lib v16+)
     * but not in the lib-14 stub this extension compiles against.
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

    // ============================== Video Streams ==============================
    // The watch page lists its servers as <span data-embed-id="b64Label:b64Url">.
    // Only two hosts are actually resolvable without a browser:
    //   - filesforever.link (the site's default) - a gdmirror-style aggregator
    //     whose /embedhelper2.php API returns sub-host player pages (the
    //     StreamHG streamhg / StreamTape branches are the only live ones).
    //   - vidmoly - a JWPlayer page with a plain sources:[{file: m3u8}] config.
    // Abyssdub (dead DNS) and the p2pplay.pro JS dashboards yield no static
    // stream, so they are skipped before any network call.
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val episodeUrl = response.request.url.toString()

        val servers = document.select("span[data-embed-id]").mapNotNull { span ->
            val encoded = span.attr("data-embed-id")
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            runCatching {
                String(Base64.decode(parts[0], Base64.DEFAULT)).trim() to
                    String(Base64.decode(parts[1], Base64.DEFAULT)).trim()
            }.getOrNull()
        }

        val perServer: List<List<Video>> = runBlocking {
            servers.map { (label, url) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(12_000L) {
                        runCatching { resolveServer(label, url, episodeUrl) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }

        return perServer.flatten()
            .distinctBy { it.videoUrl }
            .sortedWith(
                compareBy<Video> { serverPriority(it.quality) }
                    .thenByDescending {
                        it.quality.substringAfter("• ").substringBefore("p").trim().toIntOrNull() ?: 0
                    },
            )
    }

    private fun resolveServer(label: String, url: String, episodeUrl: String): List<Video> = when {
        url.contains("abyssplayer.com") || url.contains("p2pplay.pro") -> emptyList()
        url.contains("filesforever.link/embed/") ->
            resolveFilesForever(url.substringAfterLast("/"))
        url.contains("vidmoly") -> resolveVidmoly(url, episodeUrl, label)
        else -> extractDirectVideoUrls(tryGet(url, episodeUrl).orEmpty(), url, episodeUrl, label)
    }

    /**
     * filesforever.link embed: the sid page calls /embedhelper2.php which
     * answers {"sources":{key:{siteUrl,friendlyName,...}},"mresult":<b64 json>}
     * where mresult maps source key -> file code. The endpoint 302-redirects
     * to pro.iqsmartgames.com, and OkHttp turns a redirected POST into GET,
     * so the API is posted to directly.
     *
     * Only StreamHG (hanerix) and StreamTape players are live; the rest are
     * either dead (abyss), paywalled dashboards (dood, byse, rpmshare, upns,
     * strp2p) or need real browser JS, and are skipped before any fetch.
     */
    private fun resolveFilesForever(sid: String): List<Video> {
        if (sid.isBlank()) return emptyList()
        return runCatching {
            val form = FormBody.Builder()
                .add("sid", sid)
                .add("UserFavSite", "")
                .add("currentDomain", "[\"www.desidubanime.me\",\"filesforever.link\"]")
                .build()
            val ffHeaders = headers.newBuilder()
                .set("Referer", "https://filesforever.link/embed/$sid")
                .set("Origin", "https://filesforever.link")
                .withWebviewCookies("https://filesforever.link/")
                .build()
            val body = client.newCall(
                Request.Builder()
                    .url("https://pro.iqsmartgames.com/embedhelper2.php")
                    .post(form)
                    .headers(ffHeaders)
                    .build(),
            ).execute().use { it.body.string() }

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@runCatching emptyList<Video>()
            val sources = json.optJSONObject("sources")
                ?: return@runCatching emptyList<Video>()
            val fileCodes = runCatching {
                JSONObject(String(Base64.decode(json.optString("mresult"), Base64.DEFAULT)))
            }.getOrNull() ?: return@runCatching emptyList<Video>()

            val resolved = mutableListOf<List<Video>>()
            for (key in sources.keys()) {
                if (key !in LIVE_SOURCE_KEYS) continue
                val meta = sources.optJSONObject(key) ?: continue
                val code = fileCodes.optString(key)
                if (code.isBlank()) continue
                val siteUrl = meta.optString("siteUrl").replace("\\/", "/")
                if (siteUrl.isBlank()) continue
                val playerUrl = siteUrl + code
                val name = when (key) {
                    "smwh" -> "StreamHG"
                    "strmtp" -> "StreamTape"
                    else -> key.uppercase()
                }
                resolved += when (key) {
                    "strmtp" -> resolveStreamTape(playerUrl, name, sid)
                    else -> resolvePackedHls(playerUrl, name, "https://filesforever.link/embed/$sid")
                }
            }
            resolved.flatten()
        }.getOrDefault(emptyList())
    }

    /** StreamTape: the /e/<code> page hides the direct file link in a div. */
    private fun resolveStreamTape(playerUrl: String, name: String, sid: String): List<Video> {
        val html = tryGet(playerUrl, "https://filesforever.link/embed/$sid") ?: return emptyList()
        val link = STREAMTAPE_LINK_REGEX.find(html)?.value ?: return emptyList()
        val direct = "https://streamtape.site/$link"
        val reqHeaders = headers.newBuilder()
            .set("Referer", playerUrl)
            .withWebviewCookies("https://streamtape.site/")
            .build()
        return listOf(Video(direct, name, direct, headers = reqHeaders))
    }

    /** vidmoly: JWPlayer page with a plain sources:[{file: '...m3u8'}] config. */
    private fun resolveVidmoly(embedUrl: String, episodeUrl: String, label: String): List<Video> {
        val html = tryGet(embedUrl, episodeUrl) ?: return emptyList()
        val masterUrl = VIDMOLY_SOURCES_REGEX.find(html)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(html)?.value
            ?: return emptyList()
        val reqHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .withWebviewCookies(embedUrl)
            .build()
        return buildHlsVideos(masterUrl, label.ifBlank { "VMoly" }, reqHeaders, emptyList())
    }

    /**
     * Last-resort extractor for hosts we don't fully support. Any HLS URL
     * found (packed player or inline in the page) still goes through the
     * quality expander so users get per-resolution entries.
     */
    private fun extractDirectVideoUrls(html: String, hostUrl: String, referer: String, label: String): List<Video> {
        if (html.isBlank()) return emptyList()
        val hostLabel = label.ifBlank {
            runCatching { java.net.URI(hostUrl).host ?: "Server" }.getOrNull() ?: "Server"
        }
        val reqHeaders = headers.newBuilder()
            .set("Referer", referer)
            .withWebviewCookies(hostUrl)
            .build()
        decodePackedStreamUrl(html, hostUrl)?.let { master ->
            return buildHlsVideos(master, hostLabel, reqHeaders, emptyList())
        }
        M3U8_REGEX.find(html)?.let { match ->
            return buildHlsVideos(match.value, hostLabel, reqHeaders, emptyList())
        }
        return emptyList()
    }

    private fun tryGet(url: String, referer: String): String? {
        return runCatching {
            val requestHeaders = headers.newBuilder()
                .set("Referer", referer)
                .withWebviewCookies(url)
                .build()
            client.newCall(GET(url, requestHeaders)).execute().use { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        }.getOrNull()
    }

    private fun resolvePackedHls(playerUrl: String, name: String, referer: String): List<Video> {
        val html = tryGet(playerUrl, referer) ?: return emptyList()
        // Expired/deleted files return a bare "File is no longer available"
        // page with no packer - decodePackedStreamUrl then returns null.
        val masterUrl = decodePackedStreamUrl(html, playerUrl) ?: return emptyList()
        val reqHeaders = headers.newBuilder()
            .set("Referer", playerUrl)
            .withWebviewCookies(playerUrl)
            .build()
        return buildHlsVideos(masterUrl, name, reqHeaders, emptyList())
    }

    /**
     * Cloudflare fronts several of these hosters and may serve challenge
     * pages to plain OkHttp requests (browsers pass automatically). If the
     * host was ever opened in a WebView, its clearance cookie lives in the
     * shared CookieManager - forward it so requests go through.
     */
    private fun Headers.Builder.withWebviewCookies(url: String): Headers.Builder {
        runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { set("Cookie", it) }
        }
        return this
    }

    /** Lower is better; used to order servers best-to-worst. */
    private fun serverPriority(quality: String): Int = when {
        quality.startsWith("StreamHG") -> 0
        quality.startsWith("VMoly") -> 1
        quality.startsWith("StreamTape") -> 2
        else -> 3
    }

    /**
     * Expands an HLS master playlist into one Video per quality variant
     * (360p/480p/720p/1080p) so the app shows a real quality picker, plus an
     * "Auto" master entry whose in-player track selector exposes every
     * quality AND the audio renditions (Hindi/Japanese/English...).
     */
    private fun buildHlsVideos(
        masterUrl: String,
        serverName: String,
        reqHeaders: Headers,
        tracks: List<Track>,
    ): List<Video> {
        val videos = mutableListOf<Video>()
        val playlist = runCatching {
            client.newCall(GET(masterUrl, reqHeaders)).execute().use { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        }.getOrNull()
        val variants = playlist?.let { parseHlsVariants(it, masterUrl) }.orEmpty()
        val audioTracks = playlist?.let { parseHlsAudio(it, masterUrl) }.orEmpty()

        variants.forEach { (label, url) ->
            videos += Video(url, "$serverName • $label", url, headers = reqHeaders, subtitleTracks = tracks, audioTracks = audioTracks)
        }
        // The adaptive master lets the player switch quality on the fly and
        // natively exposes the audio languages.
        videos += Video(masterUrl, "$serverName • Auto", masterUrl, headers = reqHeaders, subtitleTracks = tracks, audioTracks = audioTracks)
        return videos
    }

    /** Parses #EXT-X-MEDIA:TYPE=AUDIO entries of a master playlist into Tracks. */
    private fun parseHlsAudio(playlist: String, masterUrl: String): List<Track> {
        val out = mutableListOf<Track>()
        Regex("#EXT-X-MEDIA:TYPE=AUDIO[^\\r\\n]*").findAll(playlist).forEach { match ->
            val line = match.value
            val name = Regex("NAME=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return@forEach
            val lang = Regex("LANGUAGE=\"([^\"]+)\"").find(line)?.groupValues?.get(1).orEmpty()
            val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return@forEach
            out += Track(resolveUrl(uri, masterUrl), toEnglishAudioName(name, lang))
        }
        return out.distinctBy { it.url }
    }

    /** Maps audio rendition labels (native script or ISO codes) to English. */
    private fun toEnglishAudioName(name: String, lang: String): String {
        val byLang = mapOf(
            "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu",
            "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "bn" to "Bengali",
            "ml" to "Malayalam", "kn" to "Kannada", "mr" to "Marathi",
        )
        byLang[lang.lowercase()]?.let { return it }
        val byName = mapOf(
            "हिन्दी" to "Hindi", "हिंदी" to "Hindi", "தமிழ்" to "Tamil", "తెలుగు" to "Telugu",
            "ಕನ್ನಡ" to "Kannada", "മലയാളം" to "Malayalam", "বাংলা" to "Bengali",
            "मराठी" to "Marathi", "日本語" to "Japanese", "한국어" to "Korean",
            "中文" to "Chinese", "English" to "English",
        )
        return byName[name.trim()] ?: name.trim()
    }

    /** Parses #EXT-X-STREAM-INF entries of a master playlist into (label, url) pairs. */
    private fun parseHlsVariants(playlist: String, masterUrl: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var pendingHeight: String? = null
        playlist.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingHeight = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val height = pendingHeight
                    if (height != null) {
                        out.add("${height}p" to resolveUrl(line, masterUrl))
                    }
                    pendingHeight = null
                }
            }
        }
        return out.distinctBy { it.first }
    }

    /** Resolves relative playlist/media URIs against a base (page or master) URL. */
    private fun resolveUrl(raw: String, baseUrl: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return runCatching { java.net.URI(baseUrl).resolve(raw).toString() }.getOrDefault(raw)
    }

    /**
     * The stream hosters pack their JWPlayer config with a standard Dean
     * Edwards packer. Decode the eval block and pull the HLS m3u8 URL out of
     * the JWPlayer `links`, `sources` or `file` config. The site's own player
     * code prefers links.hls4 || links.hls3 || links.hls2 - we mirror that
     * order; hls4 is usually RELATIVE (/stream/...) and must be resolved
     * against the player page.
     */
    private fun decodePackedStreamUrl(html: String, pageUrl: String): String? {
        var searchFrom = 0
        while (true) {
            val start = html.indexOf("eval(function(p,a,c,k,e,d)", searchFrom)
            if (start == -1) return null
            val segment = html.substring(start)
            val args = Regex("""}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
                .find(segment) ?: return null
            val src = args.groupValues[1]
            val radix = args.groupValues[2].toIntOrNull() ?: 10
            val count = args.groupValues[3].toIntOrNull() ?: 0
            val dict = args.groupValues[4].split("|")
            var decoded = src
            for (i in count - 1 downTo 0) {
                if (i < dict.size && dict[i].isNotEmpty()) {
                    decoded = decoded.replace(Regex("\\b" + toBase(i, radix) + "\\b"), dict[i])
                }
            }
            // Pattern 1: JWPlayer links object - site order hls4, hls3, hls2, hls.
            val linksBlock = Regex("""links\s*=\s*\{([^}]*)\}""").find(decoded)
            if (linksBlock != null) {
                for (key in listOf("hls4", "hls3", "hls2", "hls")) {
                    val linkMatch = Regex("""["']?$key["']?\s*:\s*["']([^"']+)["']""")
                        .find(linksBlock.groupValues[1])
                    if (linkMatch != null) {
                        val raw = linkMatch.groupValues[1]
                        if (raw.contains("m3u8") || raw.contains("master.txt") || raw.contains("mp4")) {
                            return resolveUrl(raw, pageUrl)
                        }
                    }
                }
            }
            // Pattern 2: file: "https://...m3u8" (generic JWPlayer config)
            val fileMatch = Regex("""["']?file["']?\s*:\s*"([^"]*(?:m3u8|\.mp4)[^"]*)""").find(decoded)
            if (fileMatch != null) {
                return resolveUrl(fileMatch.groupValues[1], pageUrl)
            }
            // Pattern 3: sources:[{file:"https://...m3u8"}]
            val sourcesMatch = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*"([^"]+)""").find(decoded)
            if (sourcesMatch != null) {
                return resolveUrl(sourcesMatch.groupValues[1], pageUrl)
            }
            searchFrom = start + 10
        }
    }

    private fun toBase(n: Int, radix: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (n == 0) return "0"
        var num = n
        var out = ""
        while (num > 0) {
            out = digits[num % radix] + out
            num /= radix
        }
        return out
    }

    // ============================== Filters ==============================
    // All of these map 1:1 onto the advanced_search POST fields the site's
    // own filter panel submits (verified against the live endpoint). The
    // dropdown values are the raw site slugs - readable enough and exact.

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Default", "Title", "Release Date", "Rating", "Popularity", "Favorite", "Updated"),
    ) {
        // The site's "Default" option sends an empty orderby.
        val param: String get() = when (state) {
            1 -> "title"
            2 -> "date"
            3 -> "rating"
            4 -> "popular"
            5 -> "favorite"
            6 -> "updated"
            else -> ""
        }
    }

    private class OrderFilter : AnimeFilter.Select<String>(
        "Order",
        arrayOf("Descending", "Ascending"),
    ) {
        val param: String get() = if (state == 1) "ASC" else "DESC"
    }

    // The lib-14 stub's AnimeFilter.CheckBox is abstract, so a concrete
    // subclass is required (same pattern as the other extensions).
    private open class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    // Multi-select checkbox groups - the site natively accepts repeated
    // genre[]/status[]/type[]/season[] params, so every checked box is sent
    // as its own form field.
    private open class MultiSelectFilter(
        name: String,
        private val field: String,
        values: Array<String>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        name,
        values.map { CheckBoxVal(it) },
    ) {
        fun toParams(): List<Pair<String, String>> =
            state.filter { it.state }.map { field to it.name }
    }

    private class GenreFilter : MultiSelectFilter(
        "Genres",
        "genre[]",
        arrayOf(
            "action", "action-adventure", "adult-cast", "adventure", "animation",
            "anthropomorphic", "award-winning", "comedy", "crime", "crossdressing",
            "detective", "drama", "ecchi", "educational", "family", "fantasy",
            "gag-humor", "gore", "harem", "high-stakes-game", "historical",
            "history", "horror", "idols-male", "isekai", "love-polygon",
            "love-status-quo", "mahou-shoujo", "mecha", "medical", "military",
            "mystery", "mythology", "organized-crime", "parody", "psychological",
            "reincarnation", "romance", "samurai", "school", "sci-fi",
            "sci-fi-fantasy", "science-fiction", "seinen", "shounen", "showbiz",
            "slice-of-life", "space", "sports", "strategy-game", "super-power",
            "supernatural", "survival", "suspense", "team-sports", "thriller",
            "time-travel", "tv-movie", "urban-fantasy", "video-game", "villainess",
            "visual-arts", "war",
        ),
    )

    private class StatusFilter : MultiSelectFilter(
        "Status",
        "status[]",
        arrayOf("airing", "break", "completed", "not-yet-released", "unknown", "upcoming"),
    )

    private class TypeFilter : MultiSelectFilter(
        "Type",
        "type[]",
        arrayOf("movie", "ona", "ova", "tv"),
    )

    private class SeasonFilter : MultiSelectFilter(
        "Season",
        "season[]",
        arrayOf("winter", "spring", "summer", "fall"),
    )

    private class YearFromFilter : AnimeFilter.Text("Year from") {
        fun toParams(): List<Pair<String, String>> = state.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { listOf("year_from" to it) }
            ?: emptyList()
    }

    private class YearToFilter : AnimeFilter.Text("Year to") {
        fun toParams(): List<Pair<String, String>> = state.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { listOf("year_to" to it) }
            ?: emptyList()
    }

    override fun getFilterList() = AnimeFilterList(
        SortFilter(),
        OrderFilter(),
        GenreFilter(),
        StatusFilter(),
        TypeFilter(),
        SeasonFilter(),
        YearFromFilter(),
        YearToFilter(),
    )

    private fun sortParam(filters: AnimeFilterList): String =
        filters.filterIsInstance<SortFilter>().firstOrNull()?.param ?: ""

    private fun orderParam(filters: AnimeFilterList): String =
        filters.filterIsInstance<OrderFilter>().firstOrNull()?.param ?: "DESC"

    // ============================== Companion ==============================

    companion object {
        // The search nonce lives in the inline kiraConfig of any page:
        // var kiraConfig = {...,"nonce":{...,"search_actions":"9d6223d1c1"}}.
        private val NONCE_REGEX = Regex(""""search_actions":"([0-9a-f]+)"""")

        // Series page URL inside a card's Info button onclick handler.
        private val ANIME_URL_REGEX = Regex("""https?://[^'"]+/anime/[^'"]+""")

        private val M3U8_REGEX = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")

        // vidmoly JWPlayer config: sources: [{ file: 'https://...master.m3u8' }]
        private val VIDMOLY_SOURCES_REGEX =
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*'([^']+\.m3u8[^']*)'""")

        // streamtape embed: <div id="ideoolink">/streamtape.site/get_video?id=..</div>
        private val STREAMTAPE_LINK_REGEX = Regex("""get_video\?id=[^"'<\s]+""")

        // filesforever source keys that actually resolve to a playable stream.
        // abys = dead DNS, flmn/byse = no static player page, ddstm = dood
        // (token dance), rpmshre/upnshr/strmp2 = JS-only dashboards.
        private val LIVE_SOURCE_KEYS = listOf("smwh", "strmtp")

        /**
         * Deterministic source id (same scheme as the other extensions in this
         * repo): ((name.hashCode() * 31 + lang.hashCode()) * 31 + versionCode),
         * kept positive via abs(). ALWAYS call it with versionCode = 1 so the
         * id stays stable across extVersionCode bumps.
         */
        private fun generateId(name: String, lang: String, versionCode: Int): Long =
            abs((name.hashCode().toLong() * 31 + lang.hashCode()) * 31 + versionCode)
    }
}
