package eu.kanade.tachiyomi.animeextension.all.toonhub4u

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import java.net.URLEncoder

class ToonHub4u : AnimeHttpSource() {

    override val name = "ToonHub4u (𝕬𝕽)"

    override val baseUrl = "https://toonhub4u.co"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("toonhub4u", "all", 1)) so the app maps the
    // index entry to the installed extension across version bumps.
    override val id: Long = 2128601802998256037L

    private val streamBase = "https://toon-stream.site"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        // The app fetches thumbnails with these headers (no browser Referer),
        // and i.ibb.co hotlink protection requires a toonhub4u.co referer.
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/category/anime/anime-series/page/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePostList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/category/animated/animation-movies/page/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parsePostList(response)

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = filters.find { it is GenreFilter } as? GenreFilter
        if (query.isBlank() && genre != null && genre.state > 0) {
            return GET("$baseUrl/category/${genre.toPath()}/page/$page/", headers)
        }
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePostList(response)

    private fun parsePostList(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animeList = document.select("li.post-item").mapNotNull { item ->
            val link = item.selectFirst("a.post-thumb") ?: return@mapNotNull null
            val url = link.attr("href")
            if (url.isBlank()) return@mapNotNull null
            val img = link.selectFirst("img")
            SAnime.create().apply {
                title = item.selectFirst("h2.post-title a")?.text()
                    ?: img?.attr("alt")
                    ?: return@mapNotNull null
                // Site titles carry a trailing "Download 480p, 720p & 1080p ..." tag.
                this.title = cleanTitle(title)
                this.url = url
                // FIFU lazy-loads some images; fall back to data-src when src is empty.
                val rawThumb = img?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-src")
                    ?: img?.attr("data-lazy-src")
                // i.ibb.co is SNI-blocked on some networks (the app's OkHttp can't
                // reach it, though browsers can via QUIC). Route those thumbnails
                // through the wsrv.nl image proxy, which is reachable everywhere.
                thumbnail_url = proxyImage(rawThumb)
            }
        }
        val hasNextPage = document.select("li.the-next-page").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    /**
     * i.ibb.co is SNI-blocked on some networks: the app's OkHttp TLS handshake
     * hangs (browsers work around it via QUIC/ECH), so posters never load.
     * wsrv.nl fetches the image server-side and is reachable everywhere, so
     * rewrite i.ibb.co thumbnails through it. Other hosts pass through.
     */
    private fun proxyImage(url: String?): String? {
        if (url == null || !url.contains("i.ibb.co")) return url
        // w=400 shrinks the download (~6x smaller) while keeping aspect ratio;
        // the app still center-crops to its card ratio.
        return "https://wsrv.nl/?url=" + URLEncoder.encode(url, "UTF-8") + "&w=400"
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
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            title = cleanTitle(document.selectFirst("h1.entry-title")?.text().orEmpty())
            // The first entry-content image is the site's real poster: a portrait
            // TMDB image (e.g. 296x444). Prefer it over the landscape og:image so
            // the details cover matches the site and isn't cropped by the app.
            val poster = document.selectFirst("div.entry-content img")
            val rawThumb = poster?.attr("src")?.takeIf { it.isNotBlank() }
                ?: poster?.attr("data-src")
                ?: poster?.attr("data-lazy-src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            thumbnail_url = proxyImage(rawThumb)
            // The entry-content also contains every episode's download blocks;
            // keep only the real info: the metadata paragraph and the synopsis.
            val meta = document.select("div.entry-content p").firstOrNull {
                it.text().trim().startsWith("Genre:")
            }?.text()?.trim()
            val synopsis = document.select("div.entry-content p").firstOrNull {
                it.text().trim().startsWith("Synopsis:", ignoreCase = true)
            }?.text()?.trim()?.substringAfter(":")?.trim()
            description = listOfNotNull(synopsis, meta).joinToString("\n\n")
            genre = document.select("span.post-categories a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val postUrl = response.request.url.toString()
        val document = Jsoup.parse(response.body.string())
        val content = document.selectFirst(".entry-content") ?: document

        // First stream link gives the show slug + season used to build the
        // per-episode ToonStream URLs (<slug>-<season>x<episode>).
        var slug = ""
        var season = 1
        val streamLink = content.select("a[href*='toonstream'], a[href*='toon-stream']").firstOrNull()
        if (streamLink != null) {
            val m = Regex("""episode/(.+?)-(\d+)x\d+/?$""").find(streamLink.attr("href"))
            if (m != null) {
                slug = m.groupValues[1]
                season = m.groupValues[2].toIntOrNull() ?: 1
            }
        }

        var currentEp = 0
        val seen = LinkedHashMap<Int, SEpisode>()
        content.select("strong, b, a").forEach { el ->
            when {
                el.tagName() == "strong" || el.tagName() == "b" -> {
                    Regex("""(?i)episode\s*(\d+)""").find(el.text())?.let {
                        currentEp = it.groupValues[1].toIntOrNull() ?: currentEp
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("href")
                    val isStream = href.contains("toonstream") || href.contains("toon-stream")
                    val isDownload = href.contains("gdmirrorbot")
                    if ((isStream || isDownload) && currentEp > 0 && !seen.containsKey(currentEp)) {
                        seen[currentEp] = SEpisode.create().apply {
                            name = "Episode $currentEp"
                            episode_number = currentEp.toFloat()
                            url = "$postUrl|$currentEp|$slug|$season"
                            date_upload = System.currentTimeMillis()
                        }
                    }
                }
            }
        }

        // Newest episode first.
        return seen.values.sortedByDescending { it.episode_number }
    }

    // ============================== Video Streams ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl, headersBuilder().add("X-Tachiyomi-Episode-Url", episode.url).build())
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.header("X-Tachiyomi-Episode-Url") ?: return emptyList()
        val parts = episodeUrl.split("|")
        if (parts.size < 4) return emptyList()
        val postUrl = parts[0]
        val epNum = parts[1]
        val slug = parts[2]
        val season = parts[3].toIntOrNull() ?: 1

        val videos = mutableListOf<Video>()
        if (slug.isNotBlank()) {
            videos += resolveStreamServers(slug, season, epNum)
        }

        // The post's download links are multi-server mirror pages (Filemoon,
        // streamhg, krakenfiles, ...). Resolve them into playable streams so
        // the list offers more than just the ToonStream servers.
        videos += resolveMirrorStreams(postUrl, epNum)

        return videos
    }

    private fun resolveStreamServers(slug: String, season: Int, epNum: String): List<Video> {
        val videos = mutableListOf<Video>()
        val episodePageUrl = "$streamBase/episode/$slug-${season}x$epNum/"
        val streamHeaders = headers.newBuilder()
            .set("Referer", "$streamBase/")
            .build()

        // Step 1: Get embed paths from the episode page.
        // The page uses data-src (lazy-loaded) iframes pointing to /embed/XXXX.
        val embeds = try {
            client.newCall(GET(episodePageUrl, streamHeaders)).execute().use { response ->
                val doc = Jsoup.parse(response.body.string())
                doc.select("iframe").mapNotNull { frame ->
                    val src = frame.attr("src").ifBlank { frame.attr("data-src") }
                    src.takeIf { it.startsWith("/embed/") }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Step 2: Each /embed/XXXX on toon-stream.site redirects to
        // gdmirrorbot.nl/embed/XXXX which loads an iframe to the same.
        // We POST to gdmirrorbot's embedhelper2.php API (which 302-redirects
        // to pro.iqsmartgames.com/embedhelper2.php) to get a JSON with
        // source names, their site URLs, and base64-encoded file codes.
        embeds.take(4).forEach { embedPath ->
            val embedId = embedPath.substringAfter("/embed/")
            runCatching {
                videos += resolveGdMirrorSources(embedId, streamHeaders)
            }
        }
        return videos
    }

    /**
     * Calls the gdmirrorbot.nl embedhelper2.php API to get the list of
     * streaming sources (streamhg, filemoon, etc.), then resolves each
     * source's player page to extract the actual HLS/MP4 URL.
     */
    private fun resolveGdMirrorSources(embedId: String, baseHeaders: Headers): List<Video> {
        val videos = mutableListOf<Video>()
        val apiHeaders = baseHeaders.newBuilder()
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("Origin", "https://gdmirrorbot.nl")
            .set("Referer", "https://gdmirrorbot.nl/embed/$embedId")
            .build()

        val form = FormBody.Builder()
            .add("sid", embedId)
            .add("UserFavSite", "")
            .add("currentDomain", "[\"toon-stream.site\",\"gdmirrorbot.nl\"]")
            .build()

        val apiResponse = try {
            client.newCall(
                Request.Builder()
                    .url("https://gdmirrorbot.nl/embedhelper2.php")
                    .post(form)
                    .headers(apiHeaders)
                    .build(),
            ).execute().use { it.body.string() }
        } catch (e: Exception) {
            return emptyList()
        }

        // Parse the JSON response to get source names, site URLs, and file codes.
        val sourcesRegex = Regex(""""(\w+)":\{[^}]*?"siteUrl"\s*:\s*"([^"]+)"[^}]*?"""")
        val mresultMatch = Regex(""""mresult"\s*:\s*"([^"]+)"""").find(apiResponse)
        val fileCodes = mutableMapOf<String, String>()
        if (mresultMatch != null) {
            val decoded = try {
                android.util.Base64.decode(mresultMatch.groupValues[1], android.util.Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
            } catch (e: Exception) { "" }
            // mresult is like {"smwh":"elc6m7opk5v7","flmn":"s7k6o0e3b5k2"}
            Regex(""""(\w+)"\s*:\s*"([^"]+)"""").findAll(decoded).forEach { m ->
                fileCodes[m.groupValues[1]] = m.groupValues[2]
            }
        }

        for (match in sourcesRegex.findAll(apiResponse)) {
            val sourceKey = match.groupValues[1]
            val siteUrl = match.groupValues[2]
            val fileCode = fileCodes[sourceKey] ?: continue
            val friendlyName = getFriendlyName(sourceKey)
            runCatching {
                val sourceHeaders = baseHeaders.newBuilder()
                    .set("Referer", "https://gdmirrorbot.nl/embed/$embedId")
                    .build()
                val playerUrl = "$siteUrl$fileCode"
                val playerHtml = client.newCall(GET(playerUrl, sourceHeaders)).execute().use {
                    it.body.string()
                }
                // The player page uses a Dean Edwards packer (eval(function(p,a,c,k,e,d)))
                // containing JWPlayer config with HLS m3u8 URLs.
                val streamUrl = decodePackedStreamUrl(playerHtml) ?: return@runCatching
                val subtitles = decodeCaptions(playerHtml).filter { it.second.endsWith(".vtt") }
                val tracks = subtitles.map { Track(it.second, it.first) }
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

    private fun resolveMirrorStreams(postUrl: String, epNum: String): List<Video> {
        val videos = mutableListOf<Video>()
        runCatching {
            val doc = client.newCall(GET(postUrl, headers)).execute().use { response ->
                Jsoup.parse(response.body.string())
            }
            val content = doc.selectFirst(".entry-content") ?: doc
            var currentEp = 0
            val mirrorLinks = mutableListOf<String>()
            content.select("strong, b, a").forEach { el ->
                when {
                    el.tagName() == "strong" || el.tagName() == "b" -> {
                        Regex("""(?i)episode\s*(\d+)""").find(el.text())?.let {
                            currentEp = it.groupValues[1].toIntOrNull() ?: currentEp
                        }
                    }
                    el.tagName() == "a" -> {
                        val href = el.attr("href")
                        if (href.contains("gdmirrorbot") && currentEp.toString() == epNum) {
                            mirrorLinks += href
                        }
                    }
                }
            }
            // The last mirror link of the episode is the highest quality
            // (the site lists 480p, 720p, 1080p in order).
            mirrorLinks.lastOrNull()?.let { resolveMirrorPage(it, videos) }
        }
        return videos
    }

    /**
     * The gdmirrorbot download links on the toonhub4u.co post page now route
     * through the same embedhelper2.php API as the ToonStream embeds. We
     * extract the embed ID from the gdmirrorbot URL and resolve it the same
     * way as resolveGdMirrorSources.
     */
    private fun resolveMirrorPage(mirrorLink: String, videos: MutableList<Video>) {
        runCatching {
            // gdmirrorbot URLs look like https://gdmirrorbot.nl/embed/vk6h2nh
            // or just have the ID in the path.
            val embedId = mirrorLink.substringAfterLast("/").substringBefore("?")
            if (embedId.isBlank()) return@runCatching
            videos += resolveGdMirrorSources(embedId, headers)
        }
    }

    /**
     * The stream source page (streamhg, filemoon, etc.) packs its JWPlayer
     * config with a standard Dean Edwards packer. Decode the eval block and
     * pull the HLS m3u8 URL out of the JWPlayer `links` or `sources` config.
     *
     * The decoded JS typically looks like:
     *   var links={"hls2":"https://...master.m3u8?t=...","hls3":"..."};
     *   jwplayer("vplayer").setup({sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}],...});
     *
     * We extract the first m3u8 URL from the links object (preferring hls2,
     * the primary CDN). We also try the `file:` pattern for other packers.
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
            // Pattern 1: JWPlayer links JSON (streamhg style)
            //   "hls2":"https://...master.m3u8?t=..."
            for (key in listOf("hls2", "hls3", "hls4", "hls")) {
                val linkMatch = Regex(""""$key"\s*:\s*"(https?://[^"]+)"""").find(decoded)
                if (linkMatch != null && (linkMatch.groupValues[1].contains("m3u8") || linkMatch.groupValues[1].contains("mp4"))) {
                    return linkMatch.groupValues[1]
                }
            }
            // Pattern 2: file: "https://...m3u8" (generic JWPlayer config)
            val fileMatch = Regex("""["']?file["']?\s*:\s*"([^"]*(?:m3u8|\.mp4)[^"]*)"""").find(decoded)
            if (fileMatch != null) {
                return fileMatch.groupValues[1]
            }
            // Pattern 3: sources:[{file:"https://...m3u8"}]
            val sourcesMatch = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*"([^"]+)"""").find(decoded)
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
                val tracks = Regex("""file\s*:\s*"([^"]+\.vtt)"[^}]*?label\s*:\s*"([^"]*)"""").findAll(decoded)
                tracks.forEach { m ->
                    out.add(Pair(m.groupValues[2], m.groupValues[1]))
                }
                searchFrom = start + 10
            }
        }
        return out
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""\s+Download.*""", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    override fun videoUrlRequest(video: Video): Request = GET(video.videoUrl.orEmpty(), headers)

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(GenreFilter())

    // The site's real genre taxonomy from /category/gener/, plus the top-level
    // content categories. Slugs map directly to category URLs.
    private class GenreFilter : AnimeFilter.Select<String>(
        "Category",
        arrayOf(
            "All",
            // Top-level content types
            "Anime Series",
            "Anime Movies",
            "Animated Series",
            "Animation Movies",
            "Channel List",
            // Genres (site taxonomy)
            "18+",
            "Action",
            "Advanture",
            "Big Magic",
            "Comedy",
            "Drama",
            "Ecchi",
            "Family",
            "Fantasy",
            "Harem",
            "Hentai",
            "Horror",
            "Magical Animated",
            "Martial Arts",
            "Mystery",
            "Romance",
            "Sci-Fic",
            "Shounen",
            "Supernatural",
            "Thriller",
        ),
    ) {
        fun toPath() = when (state) {
            1 -> "anime/anime-series"
            2 -> "anime/anime-movies"
            3 -> "animated/animated-series"
            4 -> "animated/animation-movies"
            5 -> "channel-list"
            6 -> "gener/18"
            7 -> "gener/action"
            8 -> "gener/advanture"
            9 -> "gener/big-magic"
            10 -> "gener/comedy"
            11 -> "gener/drama-gener"
            12 -> "gener/ecchi"
            13 -> "gener/family"
            14 -> "gener/fantasy"
            15 -> "gener/harem"
            16 -> "gener/hentai"
            17 -> "gener/horror"
            18 -> "gener/magical-animated"
            19 -> "gener/martial-arts"
            20 -> "gener/mystery"
            21 -> "gener/romance"
            22 -> "gener/sci-fic"
            23 -> "gener/shounen"
            24 -> "gener/supernatural"
            25 -> "gener/thriller"
            else -> ""
        }
    }
}
