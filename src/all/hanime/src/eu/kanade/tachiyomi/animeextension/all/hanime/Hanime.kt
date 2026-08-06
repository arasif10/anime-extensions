package eu.kanade.tachiyomi.animeextension.all.hanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class Hanime : ParsedAnimeHttpSource() {

    override val name = "Hanime"

    override val baseUrl = "https://hanime.tv"

    override val lang = "all"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Cookie", "inter=1")
        .add("Origin", "https://hanime.tv")
        .add("Referer", "$baseUrl/")

    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://hanime.tv/")
        .set("Origin", "https://hanime.tv")
        .build()

    // ============================== Popular Anime ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/browse/trending?page=$page", headers)
    }

    override fun popularAnimeSelector(): String = "a[href*=/videos/hentai/]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val href = element.attr("href")
            url = if (href.startsWith("http")) {
                "/" + href.substringAfter("/videos/hentai/")
            } else if (href.startsWith("/videos/hentai/")) {
                href
            } else {
                "/videos/hentai/" + href.trimStart('/')
            }
            title = element.attr("title").ifEmpty {
                element.selectFirst("img")?.attr("alt") ?: ""
            }.replace("Watch ", "").replace(" hentai stream online HD 1080p, 720p", "").trim()
            thumbnail_url = element.selectFirst("img")?.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector(): String? = popularAnimeSelector()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animeList = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }.distinctBy { it.url }

        val hasNextPage = animeList.size >= 12
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest Updates ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/browse/seasons?page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animeList = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }.distinctBy { it.url }

        val hasNextPage = animeList.size >= 12
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/browse/trending?page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ============================== Details ==============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        return GET(url, headers)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.hvpi-cover, img.cover")?.attr("src")

            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.select("div.hvpist-description p, div.description p, p.text-gray-400").text()

            genre = document.select("a[href*=/genres/], a[href*=/tags/], div.tags a, span.tag").joinToString { it.text() }
            author = document.selectFirst("a[href*=/brands/], a.brand")?.text() ?: ""
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "html"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = "Episode 1"
            episode_number = 1f
            url = element.ownerDocument()?.location() ?: ""
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Episode 1"
            episode_number = 1f
            url = response.request.url.encodedPath
        }
        return listOf(episode)
    }

    // ============================== Video Streams ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        return GET(url, headers)
    }

    override fun videoListSelector(): String = "video source, iframe"

    override fun videoFromElement(element: Element): Video {
        val videoUrl = element.attr("src")
        val quality = if (element.tagName() == "iframe") "Embed Server" else "720p"
        return Video(videoUrl, quality, videoUrl, headers = videoHeaders())
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val document = Jsoup.parse(html)
        val videoList = mutableListOf<Video>()
        val vHeaders = videoHeaders()

        // 1. Extract direct <video source> elements
        document.select("video source").forEach { element: Element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                val quality = element.attr("res").ifEmpty { element.attr("label") }.ifEmpty { "720p" }
                videoList.add(Video(src, quality, src, headers = vHeaders))
            }
        }

        // 2. Extract <iframe> embed servers
        document.select("iframe").forEach { element: Element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                videoList.add(Video(src, "Embed Server", src, headers = vHeaders))
            }
        }

        // 3. Extract .m3u8 or .mp4 stream URLs from script tags using Regex
        val streamPattern = Pattern.compile("https?://[^\"'\\s]+\\.(m3u8|mp4)[^\"'\\s]*")
        val matcher = streamPattern.matcher(html)
        val foundStreams = mutableSetOf<String>()

        while (matcher.find()) {
            val url = matcher.group()
            if (foundStreams.add(url)) {
                val quality = if (url.contains("1080")) "1080p" else if (url.contains("480")) "480p" else if (url.contains("360")) "360p" else "720p"
                videoList.add(Video(url, quality, url, headers = vHeaders))
            }
        }

        // 4. Web Player Embed fallback
        val slug = response.request.url.encodedPath.substringAfterLast("/").trim()
        if (slug.isNotBlank()) {
            val playerUrl = "https://player.hanime.tv/?id=$slug"
            videoList.add(Video(playerUrl, "Hanime Web Stream", playerUrl, headers = vHeaders))
        }

        return videoList.distinctBy { it.videoUrl }
    }
}
