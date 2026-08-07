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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

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

    private fun playerVideoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://player.hanime.tv/")
        .set("Origin", "https://player.hanime.tv")
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

            // Extract real clean synopsis by filtering out SEO / site UI text
            val pTags = document.select("p")
            val synopsisParagraphs = mutableListOf<String>()
            for (p in pTags) {
                val text = p.text().trim()
                if (text.length > 20 &&
                    !text.contains("Watch ", ignoreCase = true) &&
                    !text.contains("online", ignoreCase = true) &&
                    !text.contains("account", ignoreCase = true) &&
                    !text.contains("download", ignoreCase = true) &&
                    !text.contains("Share a bug", ignoreCase = true) &&
                    !text.contains("Session data", ignoreCase = true) &&
                    !text.contains("refresh the page", ignoreCase = true) &&
                    !text.contains("playlists", ignoreCase = true) &&
                    !text.contains("cookie", ignoreCase = true)
                ) {
                    synopsisParagraphs.add(text)
                }
            }

            description = if (synopsisParagraphs.isNotEmpty()) {
                synopsisParagraphs.joinToString("\n\n")
            } else {
                document.selectFirst("meta[name=description]")?.attr("content")
                    ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            }

            genre = document.select("a[href*=/browse/tags/], a[href*=/genres/], a[href*=/tags/], div.tags a, span.tag").joinToString { it.text() }
            author = document.selectFirst("a[href*=/browse/brands/], a[href*=/brands/], a.brand")?.text() ?: ""
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
        return Video(videoUrl, quality, videoUrl, headers = playerVideoHeaders())
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val document = Jsoup.parse(html)
        val videoList = mutableListOf<Video>()
        val pHeaders = playerVideoHeaders()

        val slug = response.request.url.encodedPath.substringAfterLast("/").trim()

        // Attempt 1: Fetch signed video API manifest directly
        if (slug.isNotBlank()) {
            try {
                val timestamp = System.currentTimeMillis() / 1000L
                val input = "$timestamp,Xkdi29,https://hanime.tv,mn2,$timestamp"
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
                val hexSig = hashBytes.joinToString("") { "%02x".format(it) }

                val apiHeaders = headers.newBuilder()
                    .add("X-Signature-Version", "web2")
                    .add("X-Signature", hexSig)
                    .add("X-Time", timestamp.toString())
                    .build()

                val apiUrls = arrayOf(
                    "$baseUrl/api/v8/video?id=$slug",
                    "https://guest.freeanimehentai.net/api/v8/video?id=$slug",
                )

                for (apiUrl in apiUrls) {
                    try {
                        val apiReq = GET(apiUrl, apiHeaders)
                        val apiRes = client.newCall(apiReq).execute()
                        if (apiRes.isSuccessful) {
                            val jsonStr = apiRes.body.string()
                            if (jsonStr.contains("videos_manifest")) {
                                val jsonObj = JSONObject(jsonStr)
                                if (jsonObj.has("videos_manifest")) {
                                    val manifest = jsonObj.getJSONObject("videos_manifest")
                                    if (manifest.has("servers")) {
                                        val servers = manifest.getJSONArray("servers")
                                        for (i in 0 until servers.length()) {
                                            val server = servers.getJSONObject(i)
                                            val serverName = server.optString("name", "Server")
                                            val streams = server.getJSONArray("streams")
                                            for (j in 0 until streams.length()) {
                                                val stream = streams.getJSONObject(j)
                                                val streamUrl = stream.optString("url")
                                                val height = stream.optInt("height", 720)
                                                val kind = stream.optString("kind", "")
                                                if (streamUrl.isNotBlank() && kind != "premium_alert") {
                                                    videoList.add(Video(streamUrl, "$serverName - ${height}p", streamUrl, headers = pHeaders))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Attempt 2: Extract direct <video source> elements
        document.select("video source").forEach { element: Element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                val quality = element.attr("res").ifEmpty { element.attr("label") }.ifEmpty { "720p" }
                videoList.add(Video(src, quality, src, headers = pHeaders))
            }
        }

        // Attempt 3: Extract <iframe> embed servers
        document.select("iframe").forEach { element: Element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                videoList.add(Video(src, "Embed Server", src, headers = pHeaders))
            }
        }

        // Attempt 4: Web Player Embed fallback
        if (slug.isNotBlank()) {
            val playerUrl = "https://player.hanime.tv/?id=$slug"
            videoList.add(Video(playerUrl, "Hanime Web Player Stream", playerUrl, headers = pHeaders))
        }

        return videoList.distinctBy { it.videoUrl }
    }
}
