package eu.kanade.tachiyomi.animeextension.all.toonstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import rx.Observable

class ToonStream : AnimeHttpSource() {

    override val name = "ToonStream (𝕬𝕽)"

    override val baseUrl = "https://toon-stream.site"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("toonstream", "all", 1)) so the app maps the
    // index entry to the installed extension across version bumps.
    override val id: Long = 4164599757938560453L

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/category/anime?type=all&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePostList(response)

    // ============================== Latest Updates ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        // Page 1 is the site's real "latest episodes" feed (/home), later pages
        // continue through the anime catalogue to keep pagination working.
        return if (page == 1) {
            GET("$baseUrl/home", headers)
        } else {
            GET("$baseUrl/category/anime?type=all&page=${page - 1}", headers)
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        if (!response.request.url.encodedPath.endsWith("/home")) return parsePostList(response)
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
        return AnimesPage(items.filter { seen.add(it.url) }, false)
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
        val episodes = LinkedHashMap<Float, SEpisode>()

        // The initial season shown on the series page (normally Season 1).
        val activeSeason = document.selectFirst(".section.episodes .season-btn.active")
            ?.attr("data-season")?.toIntOrNull() ?: 1

        document.select("#episode_by_temp li").forEach { item ->
            parseEpisodeItem(item, activeSeason)?.let { episodes[it.episode_number] = it }
        }

        // Other seasons are lazy-loaded via /series/<slug>/season/<n> (AJAX) and
        // each returns a bare <li> fragment. Fetch them one by one and merge.
        document.select(".section.episodes .season-btn[data-url]").forEach { btn ->
            val season = btn.attr("data-season").toIntOrNull() ?: return@forEach
            if (season == activeSeason) return@forEach
            val seasonUrl = btn.attr("data-url").ifBlank { return@forEach }
            runCatching {
                val seasonDoc = client.newCall(
                    GET("$baseUrl$seasonUrl", headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()),
                ).execute().use { Jsoup.parse(it.body.string(), baseUrl) }
                seasonDoc.select("li").forEach { item ->
                    parseEpisodeItem(item, season)?.let { episodes[it.episode_number] = it }
                }
            }
        }

        // Newest episode first.
        return episodes.values.sortedByDescending { it.episode_number }
    }

    private fun parseEpisodeItem(item: Element, season: Int): SEpisode? {
        val link = item.selectFirst("a.lnk-blk") ?: return null
        val url = link.attr("abs:href")
        if (url.isBlank()) return null
        val match = EPISODE_URL_REGEX.find(url) ?: return null
        val epNum = match.groupValues[3].toIntOrNull() ?: return null
        return SEpisode.create().apply {
            this.url = url
            name = "Episode $epNum"
            // Encode the season so episodes from different seasons sort in order.
            episode_number = (season * 1000 + epNum).toFloat()
            date_upload = System.currentTimeMillis()
        }
    }

    // ============================== Video Streams ==============================

    /**
     * Resolves one /embed/<token> on toon-stream.site. The token page returns a
     * single <iframe> pointing at the real hoster embed (rubystm, gdmirrorbot,
     * vidmoly, youtube, ...). Each hoster is then extracted separately.
     */
    private fun resolveEmbedServer(token: String, episodeUrl: String): List<Video> {
        val embedPage = try {
            client.newCall(GET("$baseUrl/embed/$token", headers)).execute().use {
                Jsoup.parse(it.body.string(), baseUrl)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val iframe = embedPage.selectFirst("iframe")
            ?: return emptyList()
        val hostUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
        if (hostUrl.isBlank()) return emptyList()

        return when {
            hostUrl.contains("rubystm.com") || hostUrl.contains("streamruby") -> {
                val code = hostUrl.substringAfterLast("/e/").substringBefore(".html")
                if (code.isNotBlank()) resolveRubyStream(code, episodeUrl) else emptyList()
            }
            hostUrl.contains("gdmirrorbot.nl") -> {
                val embedId = hostUrl.substringAfterLast("/")
                resolveGdMirrorSources(embedId)
            }
            hostUrl.contains("youtube") || hostUrl.contains("youtu.be") -> emptyList()
            else -> {
                // Best effort: some hosts embed a plain HLS/MP4 player page.
                tryGet(hostUrl, referer = episodeUrl)?.let {
                    extractDirectVideoUrls(it, hostUrl)
                } ?: emptyList()
            }
        }
    }

    private fun resolveRubyStream(code: String, episodeUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        runCatching {
            val rubyHeaders = headers.newBuilder()
                .set("Referer", episodeUrl)
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build()
            val form = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", code)
                .add("auto", "1")
                .add("referer", episodeUrl)
                .build()
            val html = client.newCall(
                Request.Builder().url("https://rubystm.com/dl").post(form).headers(rubyHeaders).build(),
            ).execute().use { it.body.string() }

            val streamUrl = decodePackedStreamUrl(html) ?: return@runCatching
            val captions = decodeCaptions(html).filter { it.second.endsWith(".vtt") }
            val tracks = captions.map { Track(it.second, it.first) }
            videos += Video(streamUrl, "Ruby (HLS)", streamUrl, headers = rubyHeaders, subtitleTracks = tracks)
        }
        return videos
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val episodeUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        document.select(".video-player .video iframe").forEach { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
            val token = src.substringAfter("/embed/", "").substringBefore("?")
            if (token.isEmpty() || token.startsWith("http")) return@forEach

            runCatching {
                videos += resolveEmbedServer(token, episodeUrl)
            }
        }

        // Prefer real streams, then 1080p, then 720p, then the rest.
        return videos.distinctBy { it.videoUrl }.sortedWith(
            compareByDescending<Video> { it.quality.contains("1080") }
                .thenByDescending { it.quality.contains("720") }
                .thenByDescending { !it.quality.contains("YouTube") },
        )
    }
    private fun tryGet(url: String, referer: String): String? {
        return runCatching {
            val requestHeaders = headers.newBuilder()
                .set("Referer", referer)
                .build()
            client.newCall(GET(url, requestHeaders)).execute().use { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        }.getOrNull()
    }

    /**
     * Last-resort extractor for hosts we don't fully support: scan the player
     * page for plain HLS/MP4 URLs (some hosts inline the stream directly).
     */
    private fun extractDirectVideoUrls(html: String, hostUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val hostName = runCatching { java.net.URI(hostUrl).host }.getOrNull() ?: "Server"
        // Packed player pages still work through the generic decoder.
        decodePackedStreamUrl(html)?.let { url ->
            videos += Video(url, "$hostName (HLS)", url)
            return videos
        }
        Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(html)?.let {
            videos += Video(it.value, "$hostName (HLS)", it.value)
        }
        if (videos.isEmpty()) {
            Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").find(html)?.let {
                videos += Video(it.value, "$hostName (MP4)", it.value)
            }
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
     */
    private fun resolveGdMirrorSources(embedId: String): List<Video> {
        val videos = mutableListOf<Video>()
        val form = FormBody.Builder()
            .add("sid", embedId)
            .add("UserFavSite", "")
            .add("currentDomain", "[\"toon-stream.site\",\"gdmirrorbot.nl\"]")
            .build()

        val apiResponse = listOf(
            "https://gdmirrorbot.nl/embedhelper2.php",
            "https://pro.iqsmartgames.com/embedhelper2.php",
        ).firstNotNullOfOrNull { apiUrl ->
            runCatching {
                val apiHeaders = headers.newBuilder()
                    .set("Content-Type", "application/x-www-form-urlencoded")
                    .set("Origin", "https://gdmirrorbot.nl")
                    .set("Referer", "https://gdmirrorbot.nl/embed/$embedId")
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

        // Parse the JSON response to get source keys and their site URLs.
        val sourcesRegex = Regex("""\"(\w+)\":\{[^}]*?\"siteUrl\"\s*:\s*\"([^\"]+)\"""")
        val mresultMatch = Regex("""\"mresult\"\s*:\s*\"([^\"]+)\"""").find(apiResponse)
        val fileCodes = mutableMapOf<String, String>()
        if (mresultMatch != null) {
            // mresult is base64 like {"smwh":"96vhwcxomm7l","flmn":"c8jbmkk8jpcq"}
            val decoded = runCatching {
                String(android.util.Base64.decode(mresultMatch.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrDefault("")
            Regex("""\"(\w+)\"\s*:\s*\"([^\"]+)\"""").findAll(decoded).forEach { m ->
                fileCodes[m.groupValues[1]] = m.groupValues[2]
            }
        }

        for (match in sourcesRegex.findAll(apiResponse)) {
            val sourceKey = match.groupValues[1]
            val siteUrl = match.groupValues[2].replace("\\/", "/")
            val fileCode = fileCodes[sourceKey] ?: continue
            val friendlyName = getFriendlyName(sourceKey)
            runCatching {
                val sourceHeaders = headers.newBuilder()
                    .set("Referer", "https://gdmirrorbot.nl/embed/$embedId")
                    .build()
                val playerUrl = "$siteUrl$fileCode"
                val playerHtml = client.newCall(GET(playerUrl, sourceHeaders)).execute().use {
                    it.body.string()
                }
                // The player page packs its JWPlayer config with a Dean Edwards
                // packer (eval(function(p,a,c,k,e,d))) containing HLS URLs.
                val streamUrl = decodePackedStreamUrl(playerHtml) ?: return@runCatching
                val captions = decodeCaptions(playerHtml).filter { it.second.endsWith(".vtt") }
                val tracks = captions.map { Track(it.second, it.first) }
                videos += Video(
                    streamUrl,
                    "$friendlyName (HLS)",
                    streamUrl,
                    headers = sourceHeaders,
                    subtitleTracks = tracks,
                )
            }
        }
        return videos
    }

    private fun getFriendlyName(key: String): String = when (key) {
        "smwh" -> "StreamHG"
        "flmn" -> "Filemoon"
        "flls" -> "FileLions"
        "ddstm" -> "DropLoad"
        "plrx" -> "PlayerX"
        "abys" -> "Abyss"
        "strmtp" -> "StreamTape"
        "onud" -> "UpnShare"
        "vdgd" -> "VDGD"
        "vosx" -> "Voe"
        "rpmshre" -> "RPMShare"
        "kknfl" -> "KrakenFiles"
        "upnshr" -> "UpnShare"
        "strmp2" -> "StreamP2P"
        else -> key.uppercase()
    }

    /**
     * The stream source page packs its JWPlayer config with a standard Dean
     * Edwards packer. Decode the eval block and pull the HLS m3u8 URL out of
     * the JWPlayer `links`, `sources` or `file` config.
     */
    private fun decodePackedStreamUrl(html: String): String? {
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
            // Pattern 1: JWPlayer links JSON: "hls2":"https://...master.m3u8?t=..."
            for (key in listOf("hls2", "hls3", "hls4", "hls")) {
                val linkMatch = Regex("\"$key\"\\s*:\\s*\"(https?://[^\"]+)\"").find(decoded)
                if (linkMatch != null && (linkMatch.groupValues[1].contains("m3u8") || linkMatch.groupValues[1].contains("mp4"))) {
                    return linkMatch.groupValues[1]
                }
            }
            // Pattern 2: file: "https://...m3u8" (generic JWPlayer config)
            val fileMatch = Regex("""["']?file["']?\s*:\s*"([^"]*(?:m3u8|\.mp4)[^"]*)""").find(decoded)
            if (fileMatch != null) {
                return fileMatch.groupValues[1]
            }
            // Pattern 3: sources:[{file:"https://...m3u8"}]
            val sourcesMatch = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*"([^"]+)""").find(decoded)
            if (sourcesMatch != null) {
                return sourcesMatch.groupValues[1]
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

    companion object {
        // /episode/<series-slug>-<season>x<episode>/
        private val EPISODE_URL_REGEX = Regex("""/episode/(.+?)-(\d+)x(\d+)/?$""")
    }
}
