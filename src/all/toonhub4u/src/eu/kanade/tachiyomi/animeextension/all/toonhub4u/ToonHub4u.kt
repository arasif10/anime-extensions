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
        return "https://wsrv.nl/?url=" + URLEncoder.encode(url, "UTF-8")
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
            val rawThumb = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.entry-content img")?.attr("src")
                ?: document.selectFirst("div.entry-content img")?.attr("data-src")
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

        // Only the first iframe carries a plain `src`; the rest lazy-load
        // through `data-src`, so read both. Skip the YouTube trailer embed.
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

        embeds.take(6).forEachIndexed { index, embedPath ->
            runCatching {
                val embedUrl = "$streamBase$embedPath"
                val embedDoc = client.newCall(GET(embedUrl, streamHeaders)).execute().use { response ->
                    Jsoup.parse(response.body.string())
                }
                val playerSrc = embedDoc.select("iframe[src]").attr("src")
                if (playerSrc.isBlank() || !playerSrc.contains("/e/")) return@runCatching
                val host = playerSrc.substringBefore("/e/")
                val code = playerSrc.substringAfter("/e/").substringBefore(".html")

                val form = FormBody.Builder()
                    .add("op", "embed")
                    .add("file_code", code)
                    .add("auto", "1")
                    .add("referer", embedUrl)
                    .build()
                val playerHeaders = headers.newBuilder()
                    .set("Referer", "$host/e/$code.html")
                    .build()
                val playerBody = client.newCall(
                    Request.Builder().url("$host/dl").post(form).headers(playerHeaders).build(),
                ).execute().use { it.body.string() }

                val streamUrl = decodePackedStreamUrl(playerBody) ?: return@runCatching
                val subtitles = mutableListOf<Track>()
                decodeCaptions(playerBody).forEach { (label, url) ->
                    if (url.endsWith(".vtt") && !url.contains("_sli.")) {
                        subtitles.add(Track(url, label))
                    }
                }
                videos += Video(
                    streamUrl,
                    "Server ${index + 1} (HLS)",
                    streamUrl,
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                )
            }
        }
        return videos
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
     * The gdmirrorbot link redirects to a file page. The current page renders
     * its mirrors through client-side JS (tokenized `vpage` links behind
     * Cloudflare), so the old embedhelper API is gone. Best effort: scan the
     * page (and one iframe hop) for any directly-embedded media URL; silently
     * skip JS-only mirrors.
     */
    private fun resolveMirrorPage(mirrorLink: String, videos: MutableList<Video>) {
        runCatching {
            val pageUrl = client.newCall(GET(mirrorLink, headers)).execute().use { resp ->
                resp.request.url.toString()
            }
            val pageHtml = client.newCall(GET(pageUrl, headers)).execute().use { it.body.string() }
            val direct = extractStreamFromEmbed(pageUrl, pageUrl) ?: return@runCatching
            videos += Video(direct, "Mirror", direct, headers = headers)
        }
    }

    /**
     * Follows an embed page (redirects plus one iframe hop) and returns the
     * first playable media URL found, or null for JS-only players.
     */
    private fun extractStreamFromEmbed(embedUrl: String, referer: String): String? {
        val h = headers.newBuilder().set("Referer", referer).build()
        val mediaInBody = Regex("""https?://[^"'\s<>]*(?:\.m3u8|\.mp4|\.mkv)(?:[?&][^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
        val mediaExt = Regex("""\.(?:m3u8|mp4|mkv)(?:\?|$)""", RegexOption.IGNORE_CASE)
        fun mediaFrom(url: String, body: String): String? {
            mediaInBody.find(body)?.let { return it.value }
            return if (mediaExt.containsMatchIn(url)) url else null
        }
        return runCatching {
            client.newCall(GET(embedUrl, h)).execute().use { resp ->
                val firstUrl = resp.request.url.toString()
                val firstBody = resp.body.string()
                mediaFrom(firstUrl, firstBody) ?: run {
                    val frame = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(firstBody)?.groupValues?.get(1) ?: return@runCatching null
                    val next = if (frame.startsWith("http")) frame else "${embedUrl.substringBeforeLast("/")}/$frame"
                    client.newCall(GET(next, h)).execute().use { r2 ->
                        val secondUrl = r2.request.url.toString()
                        val secondBody = r2.body.string()
                        mediaFrom(secondUrl, secondBody)
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * The ToonStream player (a streamruby/fileruby clone) packs its player
     * config with a modified Dean Edwards packer whose placeholders are
     * base-36 encoded. Decode the eval block and pull the HLS `file` URL out.
     */
    private fun decodePackedStreamUrl(html: String): String? {
        var searchFrom = 0
        while (true) {
            val start = html.indexOf("eval(function(p,a,c,k,e,d)", searchFrom)
            if (start == -1) return null
            val segment = html.substring(start)
            val args = Regex("""}\('(.*)',\s*\d+,\s*(\d+),\s*'(.*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
                .find(segment) ?: return null
            val src = args.groupValues[1]
            val count = args.groupValues[2].toIntOrNull() ?: 0
            val dict = args.groupValues[3].split("|")
            var decoded = src
            for (i in count - 1 downTo 0) {
                if (i < dict.size && dict[i].isNotEmpty()) {
                    decoded = decoded.replace(Regex("\\b" + toBase36(i) + "\\b"), dict[i])
                }
            }
            // The player config keys may be quoted or not ("file": or file:),
            // depending on how the site packs it, so accept both.
            val fileMatch = Regex("""["']?file["']?\s*:\s*"([^"]*(?:m3u8|\.mp4)[^"]*)"""").find(decoded)
            if (fileMatch != null) {
                return fileMatch.groupValues[1]
            }
            searchFrom = start + 10
        }
    }

    private fun decodeCaptions(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            var searchFrom = 0
            while (true) {
                val start = html.indexOf("eval(function(p,a,c,k,e,d)", searchFrom)
                if (start == -1) break
                val segment = html.substring(start)
                val args = Regex("""}\('(.*)',\s*\d+,\s*(\d+),\s*'(.*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(segment) ?: break
                val src = args.groupValues[1]
                val count = args.groupValues[2].toIntOrNull() ?: 0
                val dict = args.groupValues[3].split("|")
                var decoded = src
                for (i in count - 1 downTo 0) {
                    if (i < dict.size && dict[i].isNotEmpty()) {
                        decoded = decoded.replace(Regex("\\b" + toBase36(i) + "\\b"), dict[i])
                    }
                }
                val tracks = Regex("""["']?file["']?\s*:\s*"([^"]+\.vtt)"[^}]*?["']?label["']?\s*:\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(decoded)
                tracks.forEach { m ->
                    out.add(Pair(m.groupValues[2], m.groupValues[1]))
                }
                searchFrom = start + 10
            }
        }
        return out
    }

    private fun toBase36(n: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (n == 0) return "0"
        var num = n
        var out = ""
        while (num > 0) {
            out = digits[num % 36] + out
            num /= 36
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
