package eu.kanade.tachiyomi.animeextension.all.toonstream

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
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
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ToonStream : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "ToonStream (𝕬𝕽)"

    override val baseUrl = "https://toon-stream.site"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("toonstream", "all", 1)) so the app maps the
    // index entry to the installed extension across version bumps.
    override val id: Long = 4164599757938560453L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override fun getSourcePreferences(): SharedPreferences = preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_KEY
            title = "TMDB API Key"
            summary = "Used for real episode air dates. Current: %s"
            setDefaultValue(TMDB_API_KEY_DEFAULT)
            dialogTitle = "TMDB API Key"
        }.also { screen.addPreference(it) }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/category/anime?type=all&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = parsePostList(response)
        if (page.animes.isEmpty()) return page
        // The site's only feed is newest-first; rank each page by real TMDB
        // popularity so Popular is a genuinely different list from Latest.
        // The lookup is cached per title and fails open (site order) on error.
        val ranked = runBlocking {
            page.animes.map { anime ->
                async(Dispatchers.IO) { anime to tmdbPopularity(anime.title) }
            }.awaitAll()
                .sortedByDescending { it.second }
                .map { it.first }
        }
        return AnimesPage(ranked, page.hasNextPage)
    }

    // ============================== Latest Updates ==============================
    // The site has exactly one feed: /home lists the latest episode posts (which
    // collapse to ~50 series) and /category/anime?type=all paginates the same
    // catalogue 16 per page. There is no separate "popular" or sortable list.
    //   - Latest = /home page 1 (real latest), then the catalogue continues with
    //     anything already shown on page 1 filtered out. hasNextPage stays true
    //     so the app keeps paging (infinite scroll).
    //   - Popular = the same catalogue, but each page is re-ranked by real TMDB
    //     popularity so it is genuinely different from Latest.
    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/home", headers)
        } else {
            // Page 2+ continue the catalogue; skip page 1 of it since /home
            // already shows the freshest items.
            GET("$baseUrl/category/anime?type=all&page=$page", headers)
        }
    }

    private val latestPage1Urls = mutableSetOf<String>()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        if (!response.request.url.encodedPath.endsWith("/home")) {
            val page = parsePostList(response)
            val fresh = page.animes.filterNot { it.url in latestPage1Urls }
            return AnimesPage(fresh, page.hasNextPage)
        }
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val items = document.select("article.post").mapNotNull { article ->
            val link = article.selectFirst("a.lnk-blk[href*='/episode/']") ?: return@mapNotNull null
            val episodeUrl = link.attr("abs:href")
            val match = EPISODE_URL_REGEX.find(episodeUrl) ?: return@mapNotNull null
            val img = article.selectFirst("img")
            SAnime.create().apply {
                title = article.selectFirst("h2.entry-title")?.text()
                    ?: img?.attr("alt")
                    ?: return@mapNotNull null
                // Turn /episode/<slug>-<s>x<e>/ into the series page URL.
                url = episodeUrl
                    .substringBeforeLast("-${match.groupValues[2]}x${match.groupValues[3]}")
                    .replace("/episode/", "/series/")
                thumbnail_url = img?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-src")
                    ?: img?.attr("data-lazy-src")
            }
        }

        val seen = LinkedHashSet<String>()
        val unique = items.filter { seen.add(it.url) }
        // Remember what page 1 showed so later catalogue pages don't repeat it.
        latestPage1Urls.clear()
        latestPage1Urls.addAll(unique.map { it.url })
        // The catalogue continues past /home, so keep paging on.
        return AnimesPage(unique, true)
    }

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = filters.find { it is TypeFilter } as? TypeFilter
        val typeParam = type?.toParam() ?: "all"

        if (query.isBlank()) {
            // Browse mode: whichever taxonomy filter (genre/language/network)
            // is selected wins - the site can't combine taxonomies in one URL.
            val path = listOf(
                (filters.find { it is GenreFilter } as? GenreFilter)?.takeIf { it.state > 0 }?.toPath(),
                (filters.find { it is LanguageFilter } as? LanguageFilter)?.takeIf { it.state > 0 }?.toPath(),
                (filters.find { it is NetworkFilter } as? NetworkFilter)?.takeIf { it.state > 0 }?.toPath(),
            ).firstOrNull { !it.isNullOrBlank() } ?: "anime"

            return GET("$baseUrl/category/$path?type=$typeParam&page=$page", headers)
        }

        return GET("$baseUrl/s?q=${query.trim()}&type=$typeParam&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePostList(response)

    private fun parsePostList(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val animeList = document.select("ul.post-lst li article.post").mapNotNull { article ->
            val link = article.selectFirst("a.lnk-blk") ?: return@mapNotNull null
            // abs:href resolves against baseUrl; the site's hrefs are relative
            // (/series/...) and OkHttp rejects relative request URLs.
            val url = link.attr("abs:href")
            if (url.isBlank()) return@mapNotNull null
            val img = article.selectFirst("img")
            SAnime.create().apply {
                title = article.selectFirst("h2.entry-title")?.text()
                    ?: img?.attr("alt")
                    ?: return@mapNotNull null
                this.url = url
                thumbnail_url = img?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-src")
                    ?: img?.attr("data-lazy-src")
            }
        }

        val requestedPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val maxPage = document.select("a.page-link").mapNotNull { link ->
            link.attr("href").substringAfter("page=", "").substringBefore("&").toIntOrNull()
        }.maxOrNull() ?: 0

        return AnimesPage(animeList, maxPage > requestedPage)
    }

// ============================== Details ==============================
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return super.fetchAnimeDetails(anime).map { it.apply { url = anime.url } }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return super.getAnimeDetails(anime).apply { url = anime.url }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            // The first detail-page thumbnail is the portrait TMDB poster.
            val poster = document.selectFirst("article.post.single .post-thumbnail img")
                ?: document.selectFirst(".post-thumbnail img")
            thumbnail_url = poster?.attr("src")?.takeIf { it.isNotBlank() }
                ?: poster?.attr("data-src")
                ?: poster?.attr("data-lazy-src")
            description = document.select("div.description p").joinToString("\n\n") { it.text() }
                .ifBlank { document.select("div.entry-content p").joinToString("\n\n") { it.text() } }
            genre = document.select("span.genres a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string(), baseUrl)

        // Collect every episode as plain data first so we know the total
        // season count before deciding how to name/number them.
        data class EpisodeInfo(val season: Int, val num: Int, val url: String)
        val all = mutableListOf<EpisodeInfo>()

        fun parseItems(items: List<Element>) {
            items.forEach { item ->
                val link = item.selectFirst("a.lnk-blk") ?: return@forEach
                val url = link.attr("abs:href")
                if (url.isBlank()) return@forEach
                val match = EPISODE_URL_REGEX.find(url) ?: return@forEach
                val season = match.groupValues[2].toIntOrNull() ?: 1
                val num = match.groupValues[3].toIntOrNull() ?: return@forEach
                all.add(EpisodeInfo(season, num, url))
            }
        }

        // The initial season shown on the series page (normally Season 1).
        parseItems(document.select("#episode_by_temp li").toList())

        // Other seasons are lazy-loaded via /series/<slug>/season/<n> (AJAX) and
        // each returns a bare <li> fragment. Fetch them one by one and merge.
        document.select(".section.episodes .season-btn[data-url]").forEach { btn ->
            val seasonUrl = btn.attr("data-url").ifBlank { return@forEach }
            runCatching {
                val seasonDoc = client.newCall(
                    GET("$baseUrl$seasonUrl", headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()),
                ).execute().use { Jsoup.parse(it.body.string(), baseUrl) }
                parseItems(seasonDoc.select("li").toList())
            }
        }

        // Multi-season shows use "Season X - Episode Y" names - AniZen parses
        // that pattern and renders the season switcher buttons in the episode
        // list (same convention as the MovieBox extension).
        val multiSeason = (all.maxOfOrNull { it.season } ?: 1) > 1

        // Real air dates from TMDB (the site itself exposes no dates). Shows
        // that can't be found fall back to "unknown" (0) instead of a fake
        // "just now" timestamp.
        val seriesTitle = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val airDates = resolveEpisodeDates(seriesTitle, all.maxOfOrNull { it.season } ?: 1)

        // Newest episode first: latest season on top, highest episode within it.
        return all
            .sortedWith(compareByDescending<EpisodeInfo> { it.season }.thenByDescending { it.num })
            .map { info ->
                SEpisode.create().apply {
                    url = info.url
                    name = if (multiSeason) {
                        "Season ${info.season} - Episode ${info.num}"
                    } else {
                        "Episode ${info.num}"
                    }
                    // Plain in-season number (like the MovieBox extension) -
                    // the season switcher buttons come from the "Season X -
                    // Episode Y" name pattern, and offsetting by 1000 per
                    // season makes the app report thousands of "missing"
                    // episodes between season boundaries.
                    episode_number = info.num.toFloat()
                    date_upload = airDates[Pair(info.season, info.num)] ?: 0L
                }
            }
    }

    // ================= Episode air dates (TMDB) =================
    // The site carries no per-episode dates anywhere, so real air dates come
    // from TMDB (the same database the site pulls its posters from). The show
    // is found by title search and each season's episodes are fetched once;
    // results are cached per series for the session.
    private val tmdbShowCache = mutableMapOf<String, Long>() // title(lower) -> tv id
    private val tmdbDatesCache = mutableMapOf<String, Map<Pair<Int, Int>, Long>>() // title(lower) -> (season, num) -> millis
    private val tmdbPopularityCache = mutableMapOf<String, Double>() // title(lower) -> popularity

    private fun tmdbApiKey(): String =
        preferences.getString(PREF_TMDB_KEY, TMDB_API_KEY_DEFAULT).orEmpty().trim()

    /**
     * TMDB "popularity" score for a title (used to give the Popular tab a real
     * ranking since the site itself has only one newest-first feed). The search
     * is cached per title and fails open with 0.0.
     */
    private fun tmdbPopularity(title: String): Double {
        val cacheKey = title.lowercase()
        tmdbPopularityCache[cacheKey]?.let { return it }
        val apiKey = tmdbApiKey()
        if (apiKey.isEmpty() || title.isBlank()) return 0.0
        val pop = runCatching {
            val searchUrl = "https://api.themoviedb.org/3/search/tv" +
                "?api_key=$apiKey&language=en-US&query=${Uri.encode(title)}"
            val json = JSONObject(client.newCall(GET(searchUrl)).execute().use { it.body.string() })
            val results = json.optJSONArray("results") ?: return@runCatching 0.0
            var best = 0.0
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val pop = result.optDouble("popularity", 0.0)
                if (result.optString("name").equals(title, ignoreCase = true)) {
                    best = pop
                    break
                }
                if (i == 0) best = pop
            }
            best
        }.getOrDefault(0.0)
        tmdbPopularityCache[cacheKey] = pop
        return pop
    }

    private fun resolveEpisodeDates(title: String, maxSeason: Int): Map<Pair<Int, Int>, Long> {
        val cacheKey = title.lowercase()
        tmdbDatesCache[cacheKey]?.let { return it }
        val dates = mutableMapOf<Pair<Int, Int>, Long>()
        val apiKey = tmdbApiKey()
        if (apiKey.isEmpty() || title.isBlank()) return dates

        val tvId = runCatching {
            tmdbShowCache.getOrPut(cacheKey) {
                val searchUrl = "https://api.themoviedb.org/3/search/tv" +
                    "?api_key=$apiKey&language=en-US&query=${Uri.encode(title)}"
                val json = JSONObject(client.newCall(GET(searchUrl)).execute().use { it.body.string() })
                val results = json.optJSONArray("results") ?: return@getOrPut 0L
                var first = 0L
                var best = 0L
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val id = result.optLong("id")
                    if (first == 0L) first = id
                    if (result.optString("name").equals(title, ignoreCase = true)) {
                        best = id
                        break
                    }
                }
                if (best != 0L) best else first
            }
        }.getOrDefault(0L)
        if (tvId <= 0L) return dates

        for (season in 1..maxSeason) {
            runCatching {
                val seasonUrl = "https://api.themoviedb.org/3/tv/$tvId/season/$season" +
                    "?api_key=$apiKey&language=en-US"
                val json = JSONObject(client.newCall(GET(seasonUrl)).execute().use { it.body.string() })
                val episodes = json.getJSONArray("episodes")
                for (i in 0 until episodes.length()) {
                    val ep = episodes.getJSONObject(i)
                    val num = ep.optInt("episode_number", -1)
                    val air = ep.optString("air_date")
                    if (num <= 0 || air.isBlank()) continue
                    val millis = runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .parse(air)!!.time
                    }.getOrDefault(0L)
                    if (millis > 0L) dates[Pair(season, num)] = millis
                }
            }
        }
        tmdbDatesCache[cacheKey] = dates
        return dates
    }

    // ============================== Video Streams ==============================

    /**
     * Resolves one /embed/<token> on toon-stream.site. The token page returns a
     * single <iframe> pointing at the real hoster embed (gdmirrorbot,
     * emturbovid, ...). Each hoster is then extracted separately.
     */
    private fun resolveEmbedServer(token: String, episodeUrl: String): List<Video> {
        val embedPage = try {
            val embedHeaders = headers.newBuilder()
                .withWebviewCookies("$baseUrl/")
                .build()
            client.newCall(GET("$baseUrl/embed/$token", embedHeaders)).execute().use {
                Jsoup.parse(it.body.string(), baseUrl)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val iframe = embedPage.selectFirst("iframe")
            ?: return emptyList()
        val hostUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
        if (hostUrl.isBlank()) return emptyList()

        if (DEAD_HOSTS.any { hostUrl.contains(it, ignoreCase = true) }) return emptyList()

        return when {
            hostUrl.contains("gdmirrorbot.nl") -> {
                val embedId = hostUrl.substringAfterLast("/")
                resolveGdMirrorSources(embedId)
            }
            hostUrl.contains("rubystm.com") -> {
                // The site's "Server 1" - a filehost embed that loads the
                // player via POST to /dl, serving a master with up to 720p
                // and 5 audio tracks (Hindi/Tamil/Telugu/English/Japanese).
                resolveRubyStream(hostUrl, episodeUrl)
            }
            hostUrl.contains("as-cdn26.top") -> {
                // The site's "Play" server - FireVideo player with a JSON
                // API that returns a direct HLS master (240p-1080p + 5
                // audio tracks). This is the site's best server.
                resolveFireVideo(hostUrl, episodeUrl)
            }
            else -> {
                // Best effort: some hosts embed a plain HLS/MP4 player page.
                tryGet(hostUrl, referer = episodeUrl)?.let {
                    extractDirectVideoUrls(it, hostUrl, episodeUrl)
                } ?: emptyList()
            }
        }
    }

    /**
     * FireVideo (as-cdn26.top) - the site's "Play" server. The player page
     * alone only carries a WebTorrent fallback, but its JSON API
     * (POST /player/index.php?data=<id>&do=getVideo) returns a direct HLS
     * master with the full quality ladder (240p-1080p) and 5 audio tracks
     * (Japanese/English/Telugu/Tamil/Hindi).
     */
    private fun resolveFireVideo(hostUrl: String, episodeUrl: String): List<Video> {
        val id = hostUrl.substringAfterLast("/").substringBefore("?")
        if (id.isBlank()) return emptyList()

        return runCatching {
            val form = FormBody.Builder()
                .add("hash", id)
                .add("r", episodeUrl)
                .build()
            val fvHeaders = headers.newBuilder()
                .set("Referer", hostUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .withWebviewCookies("https://as-cdn26.top/")
                .build()
            val apiBody = client.newCall(
                Request.Builder()
                    .url("https://as-cdn26.top/player/index.php?data=$id&do=getVideo")
                    .post(form)
                    .headers(fvHeaders)
                    .build(),
            ).execute().use { it.body.string() }
            val json = runCatching { JSONObject(apiBody) }.getOrNull() ?: return@runCatching emptyList<Video>()
            val masterUrl = json.optString("videoSource", "").takeIf { it.isNotBlank() }
                ?: return@runCatching emptyList<Video>()
            buildHlsVideos(masterUrl, "Play", fvHeaders, emptyList())
        }.getOrDefault(emptyList())
    }

    /**
     * StreamRuby (rubystm.com) is a standard filehost embed: the /e/<code>
     * page only holds a hidden form that POSTs to /dl with the file code,
     * and the /dl response contains the packed JWPlayer config with the HLS
     * master (variants + multi-audio renditions).
     */
    private fun resolveRubyStream(hostUrl: String, episodeUrl: String): List<Video> {
        val code = hostUrl.substringAfterLast("/")
            .substringBefore(".html")
            .substringAfterLast("-")
        if (code.isBlank()) return emptyList()

        return runCatching {
            val form = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", code)
                .add("auto", "1")
                .add("referer", episodeUrl)
                .build()
            val rubyHeaders = headers.newBuilder()
                .set("Referer", hostUrl)
                .set("Content-Type", "application/x-www-form-urlencoded")
                .withWebviewCookies("https://rubystm.com/")
                .build()
            val playerHtml = client.newCall(
                Request.Builder().url("https://rubystm.com/dl").post(form).headers(rubyHeaders).build(),
            ).execute().use { it.body.string() }

            val masterUrl = decodePackedStreamUrl(playerHtml, "https://rubystm.com/dl")
                ?: return@runCatching emptyList<Video>()
            buildHlsVideos(masterUrl, "Ruby", rubyHeaders, emptyList())
        }.getOrDefault(emptyList())
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

    /**
     * Expands an HLS master playlist into one Video per quality variant
     * (360p/480p/720p/1080p) so the app shows a real quality picker, plus an
     * "Auto" master entry whose in-player track selector exposes every
     * quality AND the dual-audio renditions (Hindi/Japanese). Audio
     * renditions are also attached as audioTracks, which AniZen lists in its
     * audio selection sheet.
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
            videos += Video(
                url,
                "$serverName • $label",
                url,
                headers = reqHeaders,
                subtitleTracks = tracks,
                audioTracks = audioTracks,
            )
        }
        // The adaptive master lets the player switch quality on the fly and
        // natively exposes both audio languages.
        videos += Video(
            masterUrl,
            "$serverName • Auto",
            masterUrl,
            headers = reqHeaders,
            subtitleTracks = tracks,
            audioTracks = audioTracks,
        )
        return videos
    }

    /**
     * Parses #EXT-X-MEDIA:TYPE=AUDIO entries of a master playlist into
     * Tracks (e.g. the Hindi/Japanese dual-audio renditions).
     */
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

    /**
     * The site's HLS masters name audio renditions in native script
     * (हिन्दी, தமிழ், తెలుగు, 日本語...). Map those - plus the LANGUAGE
     * attribute - to English labels for the app's audio picker.
     */
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

    /**
     * Parses #EXT-X-STREAM-INF entries of a master playlist into
     * (quality label, variant url) pairs.
     */
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

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val episodeUrl = response.request.url.toString()

        val tokens = document.select(".video-player .video iframe").mapNotNull { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
            val token = src.substringAfter("/embed/", "").substringBefore("?")
            token.takeIf { it.isNotEmpty() && !it.startsWith("http") }
        }

        // Resolve every server in parallel on the IO pool. Blocking OkHttp
        // calls inside plain `async` on runBlocking's single thread would run
        // sequentially - Dispatchers.IO is what makes this actually parallel.
        // Each server also gets a hard timeout so one dead hoster can never
        // stall the whole list again.
        val perServer: List<List<Video>> = runBlocking {
            tokens.map { token ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(12_000L) {
                        runCatching { resolveEmbedServer(token, episodeUrl) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }

        // Best server first, then best quality within each server (1080p >
        // 720p > 480p > 360p > 240p > Auto). "Auto" has no resolution number
        // so it sorts to the end of its server's block.
        return perServer.flatten()
            .distinctBy { it.videoUrl }
            .sortedWith(
                compareBy<Video> { serverPriority(it.quality) }
                    .thenByDescending { it.quality.substringAfter("• ").substringBefore("p").trim().toIntOrNull() ?: 0 },
            )
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

    /**
     * Last-resort extractor for hosts we don't fully support. Any HLS URL
     * found (packed player or inline in the page) still goes through the
     * quality expander so users get per-resolution entries.
     */
    private fun extractDirectVideoUrls(html: String, hostUrl: String, referer: String): List<Video> {
        val host = runCatching { java.net.URI(hostUrl).host ?: "Server" }.getOrNull() ?: "Server"
        val hostLabel = friendlyHost(host)
        val reqHeaders = headers.newBuilder()
            .set("Referer", referer)
            .withWebviewCookies(hostUrl)
            .build()

        // Packed player pages still work through the generic decoder.
        decodePackedStreamUrl(html, hostUrl)?.let { master ->
            return buildHlsVideos(master, hostLabel, reqHeaders, emptyList())
        }
        Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(html)?.let { match ->
            return buildHlsVideos(match.value, hostLabel, reqHeaders, emptyList())
        }
        val videos = mutableListOf<Video>()
        Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").find(html)?.let {
            videos += Video(it.value, "$hostLabel (MP4)", it.value, headers = reqHeaders)
        }
        return videos
    }

    /**
     * Calls the gdmirrorbot.nl embedhelper2.php API to get the list of
     * streaming sources (streamhg, filemoon, etc.), then resolves each
     * source's player page to extract the actual HLS/MP4 URL.
     *
     * The gdmirrorbot endpoint 302-redirects to pro.iqsmartgames.com, which
     * rejects GET requests, so we try both URLs directly instead of relying
     * on redirect following.
     *
     * The API response nests the sources: {"sources":{"smwh":{"siteUrl":
     * "https://hanerix.com/e/","friendlyName":"streamhg",...},"flmn":{...}}},
     * plus a base64 "mresult" map of sourceKey -> fileCode. Parse it as JSON
     * (a regex cannot handle the nesting reliably).
     */
    private fun resolveGdMirrorSources(embedId: String): List<Video> {
        val videos = mutableListOf<Video>()
        val form = FormBody.Builder()
            .add("sid", embedId)
            .add("UserFavSite", "")
            .add("currentDomain", "[\"toon-stream.site\",\"gdmirrorbot.nl\"]")
            .build()

        val apiResponse = listOf(
            "https://pro.iqsmartgames.com/embedhelper2.php",
            "https://gdmirrorbot.nl/embedhelper2.php",
        ).firstNotNullOfOrNull { apiUrl ->
            runCatching {
                val apiHeaders = headers.newBuilder()
                    .set("Content-Type", "application/x-www-form-urlencoded")
                    .set("Origin", "https://gdmirrorbot.nl")
                    .set("Referer", "https://gdmirrorbot.nl/embed/$embedId")
                    .withWebviewCookies("https://gdmirrorbot.nl/")
                    .build()
                client.newCall(
                    Request.Builder().url(apiUrl).post(form).headers(apiHeaders).build(),
                ).execute().use { it.body.string() }
            }.getOrNull()?.takeIf { body ->
                body.isNotBlank() &&
                    !body.contains("\"sources\":[]") &&
                    !body.contains("\"error\"")
            }
        } ?: return videos

        val parsed = runCatching { JSONObject(apiResponse) }.getOrNull() ?: return videos
        val sourcesObj = parsed.optJSONObject("sources") ?: return videos

        // sourceKey -> fileCode, decoded from the base64 "mresult" map.
        val fileCodes = runCatching {
            val decoded = String(
                android.util.Base64.decode(parsed.optString("mresult", ""), android.util.Base64.DEFAULT),
            )
            val map = mutableMapOf<String, String>()
            JSONObject(decoded).keys().asSequence().forEach { key ->
                map[key] = JSONObject(decoded).optString(key)
            }
            map
        }.getOrDefault(emptyMap())

        // Source key -> (player site URL, friendly label).
        data class Source(val key: String, val siteUrl: String, val label: String)
        val sources = mutableListOf<Source>()
        sourcesObj.keys().asSequence().forEach { key ->
            val sub = sourcesObj.optJSONObject(key) ?: return@forEach
            val siteUrl = sub.optString("siteUrl", "").replace("\\/", "/")
            if (siteUrl.isNotBlank()) {
                val friendly = sub.optString("friendlyName", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { getFriendlyName(it) }
                    ?: getFriendlyName(key)
                sources += Source(key, siteUrl, friendly)
            }
        }
        if (sources.isEmpty()) return videos

        // Resolve each source's player page in parallel on the IO pool, with
        // the same per-server timeout as everything else. Filemoon (flmn) is a
        // React SPA that serves no static player page and KrakenFiles (kknfl)
        // is a file hoster with no playable stream - skip both outright.
        val resolved: List<List<Video>> = runBlocking {
            sources
                .filter { it.key !in DEAD_SOURCE_KEYS }
                .map { src ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(8_000L) {
                            runCatching {
                                val fileCode = fileCodes[src.key] ?: return@runCatching emptyList<Video>()
                                val sourceHeaders = headers.newBuilder()
                                    .set("Referer", "https://gdmirrorbot.nl/embed/$embedId")
                                    .build()
                                val playerUrl = "${src.siteUrl}$fileCode"
                                val playerHtml = client.newCall(GET(playerUrl, sourceHeaders)).execute().use {
                                    it.body.string()
                                }
                                // The player page packs its JWPlayer config with a
                                // Dean Edwards packer containing HLS URLs. The site
                                // itself picks hls4 || hls3 || hls2 - we mirror that
                                // order and resolve relative (hls4) links against
                                // the player page. Expired/deleted files return a
                                // bare "File is no longer available" page with no
                                // packer, so decodePackedStreamUrl returns null and
                                // the source is skipped.
                                val masterUrl = decodePackedStreamUrl(playerHtml, playerUrl)
                                    ?: return@runCatching emptyList<Video>()
                                val captions = decodeCaptions(playerHtml).filter { it.second.endsWith(".vtt") }
                                val tracks = captions.map { Track(it.second, it.first) }
                                buildHlsVideos(masterUrl, src.label, sourceHeaders, tracks)
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                    }
                }
                .toList()
                .awaitAll()
        }
        videos += resolved.flatten()
        return videos
    }

    private fun getFriendlyName(key: String): String = when (key.lowercase()) {
        "smwh", "streamhg" -> "StreamHG"
        "flmn", "byse" -> "Filemoon"
        "flls", "earnvids" -> "FileLions"
        "ddstm" -> "DropLoad"
        "plrx" -> "PlayerX"
        "abys" -> "Abyss"
        "strmtp" -> "StreamTape"
        "onud", "upnshr" -> "UpnShare"
        "vdgd" -> "VDGD"
        "vosx" -> "Voe"
        "rpmshre" -> "RPMShare"
        "kknfl", "krakenfiles" -> "KrakenFiles"
        "strmp2" -> "StreamP2P"
        "mxdp" -> "MixDrop"
        else -> key.uppercase()
    }

    /**
     * The stream source page packs its JWPlayer config with a standard Dean
     * Edwards packer. Decode the eval block and pull the HLS m3u8 URL out of
     * the JWPlayer `links`, `sources` or `file` config.
     *
     * The site's own player code prefers `links.hls4 || links.hls3 ||
     * links.hls2` - we mirror that order. hls4 is usually a RELATIVE url
     * (/stream/...) that must be resolved against the player page; hls2's
     * tokenized premilkyway links now answer 403, so it is only a last resort.
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
                    val linkMatch = Regex("""["']?$key["']?\s*:\s*["']([^"']+)["']""").find(linksBlock.groupValues[1])
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

    private fun decodeCaptions(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            var searchFrom = 0
            while (true) {
                val start = html.indexOf("eval(function(p,a,c,k,e,d)", searchFrom)
                if (start == -1) break
                val segment = html.substring(start)
                val args = Regex("""}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(segment) ?: break
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
                // JWPlayer tracks: {file:"...vtt",label:"English",kind:"captions"}
                val tracks = Regex("""file\s*:\s*"([^"]+\.vtt)"[^}]*?label\s*:\s*"([^"]*)""").findAll(decoded)
                tracks.forEach { m ->
                    out.add(Pair(m.groupValues[2], m.groupValues[1]))
                }
                searchFrom = start + 10
            }
        }
        return out
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        LanguageFilter(),
        NetworkFilter(),
    )

    // Filters mirror the site's real navigation taxonomy; slugs map directly
    // to /category/<slug> URLs (all verified returning 200).

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "All",
            "Action",
            "Adventure",
            "Animation",
            "Comedy",
            "Crime",
            "Drama",
            "Family",
            "Fantasy",
            "Horror",
            "Kids",
            "Martial Art",
            "Mystery",
            "Romance",
            "Sci-fi",
            "Sci-Fi & Fantasy",
            "Superhero",
            "Thriller",
            "War",
        ),
    ) {
        fun toPath() = when (state) {
            1 -> "action"
            2 -> "adventure"
            3 -> "animation"
            4 -> "comedy"
            5 -> "crime"
            6 -> "drama"
            7 -> "family"
            8 -> "fantasy"
            9 -> "horror"
            10 -> "kids"
            11 -> "martial-art"
            12 -> "mystery"
            13 -> "romance"
            14 -> "sci-fi"
            15 -> "sci-fi-fantasy"
            16 -> "superhero"
            17 -> "thriller"
            18 -> "war"
            else -> ""
        }
    }

    private class LanguageFilter : AnimeFilter.Select<String>(
        "Language",
        arrayOf(
            "All",
            "Hindi",
            "Tamil",
            "Telugu",
            "Fan Hindi",
            "Malayalam",
            "Kannada",
            "Bengali",
            "Marathi",
            "English",
            "Japanese",
            "Korean",
            "Chinese",
        ),
    ) {
        fun toPath() = when (state) {
            1 -> "language/hindi-language"
            2 -> "language/tamil-language"
            3 -> "language/telugu"
            4 -> "language/fan-hindi"
            5 -> "language/malyalam"
            6 -> "language/kannada"
            7 -> "language/bengali"
            8 -> "language/marathi"
            9 -> "language/english"
            10 -> "language/japaneses"
            11 -> "language/korean"
            12 -> "language/chinese"
            else -> ""
        }
    }

    private class NetworkFilter : AnimeFilter.Select<String>(
        "Network",
        arrayOf(
            "All",
            "Crunchyroll",
            "Netflix",
            "Disney",
            "Cartoon Network",
            "Nickelodeon",
            "Sony Yay",
            "Hungama",
            "ETV Bal Bharti",
            "Kids Zone Plus",
        ),
    ) {
        fun toPath() = when (state) {
            1 -> "crunchyroll"
            2 -> "netflix"
            3 -> "disney"
            4 -> "cartoon-network"
            5 -> "nickelodean"
            6 -> "sony-yay"
            7 -> "hungama"
            8 -> "etv-bal-bharti"
            9 -> "kinds-zone-pluse"
            else -> ""
        }
    }

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Movies", "Series"),
    ) {
        fun toParam() = when (state) {
            1 -> "movies"
            2 -> "series"
            else -> "all"
        }
    }

    private fun friendlyHost(host: String): String = when (host) {
        "emturbovid.com" -> "Turbovid"
        else -> host
    }

    /** Lower is better; used to order servers best-to-worst. */
    private fun serverPriority(quality: String): Int = when {
        quality.startsWith("Play") -> 0
        quality.startsWith("StreamHG") -> 1
        quality.startsWith("Ruby") -> 2
        quality.startsWith("FileLions") -> 3
        quality.startsWith("vidmoly") -> 4
        else -> 5
    }

    /**
     * Hosters that no longer serve playable streams (dead domains, JS-only
     * players or expired files). Skipped before any network call so opening
     * an episode never waits on them.
     *
     * Turbovid (emturbovid) is excluded because its "video" is actually a
     * slideshow of PNG images hosted on Google Drive - no real video player
     * can decode it.
     */
    private val DEAD_HOSTS = listOf(
        "abyssplayer.com", "blakiteapi.xyz", "cloudy.upns.one", "upns.one",
        "youtube.com", "youtu.be",
        "emturbovid.com", "turboviplay.com", "turbosplayer.com",
        "vidstreaming.xyz", "strmup.to",
    )

    /** gdmirror API source keys that never yield a playable stream. */
    private val DEAD_SOURCE_KEYS = listOf("flmn", "kknfl")

    companion object {
        private const val PREF_TMDB_KEY = "tmdb_api_key"

        // Public key from TMDB's open-source sample apps (embedded in many OSS
        // projects); users can override it in the source settings.
        private const val TMDB_API_KEY_DEFAULT = "3fd2be6f0c70a2a598f084ddfb75487c"

        // /episode/<series-slug>-<season>x<episode>/
        private val EPISODE_URL_REGEX = Regex("""/episode/(.+?)-(\d+)x(\d+)/?$""")
    }
}
