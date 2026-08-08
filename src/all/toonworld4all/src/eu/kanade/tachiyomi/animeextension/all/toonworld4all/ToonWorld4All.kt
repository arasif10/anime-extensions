package eu.kanade.tachiyomi.animeextension.all.toonworld4all

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup

class ToonWorld4All : AnimeHttpSource() {

    override val name = "ToonWorld4All"

    override val baseUrl = "https://toonworld4all.me"

    override val lang = "all"

    override val supportsLatest = true

    private val archiveUrl = "https://archive.toonworld4all.me"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ============================== Popular Anime ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animeList = document.select("article.herald-lay-b, article.herald-fa-item").map { element ->
            SAnime.create().apply {
                title = element.select("h2.entry-title a").text().ifBlank {
                    element.select("h1.entry-title a").text()
                }
                url = element.select("h2.entry-title a").attr("href").ifBlank {
                    element.select("h1.entry-title a").attr("href")
                }
                thumbnail_url = element.select("img.wp-post-image").attr("src").ifBlank {
                    element.select("img").attr("src")
                }
            }
        }

        val current = document.select("nav.herald-pagination span.current").text().toIntOrNull() ?: 1
        val hasNextPage = document.select("nav.herald-pagination a.next").isNotEmpty() ||
            document.select("nav.herald-pagination a.page-numbers").any {
                it.text().toIntOrNull()?.let { page -> page > current } ?: false
            }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest Updates ==============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ==============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("img.wp-post-image")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.select("div.herald-entry-content p").text()
                .ifBlank { document.select("div.entry-content p").text() }
            genre = document.select("span.meta-category a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string())
        val episodes = mutableListOf<SEpisode>()

        document.select("a[href*='archive.toonworld4all.me']").forEach { element ->
            val ep = SEpisode.create().apply {
                url = element.attr("href")
                name = element.closest(".mks_accordion_item")?.select(".mks_accordion_heading")?.text()
                    ?: element.parent()?.previousElementSibling()?.text()
                    ?: element.text()
            }
            episodes.add(ep)
        }

        // The show page lists episodes from first to last; reverse so the
        // newest episode is on top, matching the archive order.
        return episodes.reversed()
    }

    // ============================== Video Streams ==============================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())

        val scriptContent = document.select("script")
            .firstOrNull { it.data().contains("window.__PROPS__") }
            ?.data() ?: return emptyList()

        val propsJson = scriptContent.substringAfter("window.__PROPS__ = ")
            .substringBefore(";")

        if (propsJson.isEmpty()) return emptyList()

        val props = try {
            JSONObject(propsJson)
        } catch (e: Exception) {
            return emptyList()
        }

        val encodes = props.getJSONObject("data")
            .getJSONObject("data")
            .optJSONArray("encodes") ?: return emptyList()

        val videos = mutableListOf<Video>()
        for (i in 0 until encodes.length()) {
            val encode = encodes.getJSONObject(i)
            val resolution = encode.getString("resolution")
            val files = encode.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val file = files.getJSONObject(j)
                val hostName = file.getString("host")
                val fileLink = file.getString("link")
                val redirectUrl = if (fileLink.startsWith("/")) "$archiveUrl$fileLink" else fileLink

                val hostUrl = runCatching { resolveBridgeHops(redirectUrl) }.getOrNull()
                    ?: continue

                if (hostName.contains("HubCloud", ignoreCase = true) || hostName.contains("GDFlix", ignoreCase = true)) {
                    videos += deepExtractVideos(hostUrl, resolution, hostName)
                } else {
                    videos += Video(hostUrl, "$resolution - $hostName (Portal)", hostUrl, headers)
                }
            }
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.quality.contains("1080") }
                .thenByDescending { it.quality.contains("720") }
                .thenByDescending { !it.quality.contains("Portal") },
        )
    }

    /**
     * Follows the archive redirect link. The /redirect/ endpoint returns a small
     * page whose JSON carries the final destination (a shortener or the file
     * host page), so parse that when a plain redirect chain is not used.
     */
    private fun resolveBridgeHops(url: String): String? {
        val bridgeHeaders = headers.newBuilder()
            .set("Referer", "$archiveUrl/")
            .build()

        client.newCall(GET(url, bridgeHeaders)).execute().use { response ->
            val finalUrl = response.request.url.toString()

            if (!finalUrl.contains("/redirect/")) {
                return finalUrl
            }

            val html = response.body.string()
            val destRegex = Regex("""\"destination\":\"(.*?)\"""")
            return destRegex.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        }
    }

    /**
     * Some file hosts (HubCloud / GDFlix) expose the real stream URL in their
     * page markup. Try to grab it; otherwise fall back to opening the host page.
     */
    private fun deepExtractVideos(hostUrl: String, res: String, hostName: String): List<Video> {
        val hostHeaders = headers.newBuilder()
            .set("Referer", hostUrl.substringBeforeLast("/") + "/")
            .build()

        client.newCall(GET(hostUrl, hostHeaders)).execute().use { response ->
            val html = response.body.string()

            val tokRegex = Regex("""href="([^"]+tok=[^"]+)" """, RegexOption.DOT_MATCHES_ALL)
            val downloadRegex = Regex("""\"([^"]+/download/[^"]+)\"""", RegexOption.DOT_MATCHES_ALL)
            val fileRegex = Regex("""file:\s*\"([^"]+)\"""", RegexOption.DOT_MATCHES_ALL)

            val streamUrl = tokRegex.find(html)?.groupValues?.get(1)
                ?: downloadRegex.find(html)?.groupValues?.get(1)
                ?: fileRegex.find(html)?.groupValues?.get(1)

            return if (streamUrl != null && !streamUrl.contains("/video/") && !streamUrl.contains("/file/")) {
                listOf(Video(streamUrl, "$res - $hostName", streamUrl, headers = hostHeaders))
            } else {
                listOf(Video(hostUrl, "$res - $hostName (Portal)", hostUrl, headers = hostHeaders))
            }
        }
    }

    override fun videoUrlRequest(video: Video): Request {
        val url = video.videoUrl?.ifBlank { baseUrl } ?: baseUrl
        return GET(url, headers)
    }

    override fun videoUrlParse(response: Response): String = response.request.url.toString()
}
