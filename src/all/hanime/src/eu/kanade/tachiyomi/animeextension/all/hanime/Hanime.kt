package eu.kanade.tachiyomi.animeextension.all.hanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    private fun streamHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", "$baseUrl")
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
    private val handshakeUrl = "https://auth.hanime.tv/api/v11/handshake"
    private val handshakeSecret = "htv-insecure-handshake-v1"
    private val handshakeAad = "htv-insecure-v1"

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP).replace("=", "")

    private fun base64UrlDecode(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> value + "=="
            3 -> value + "="
            else -> value
        }
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /**
     * Encrypts the handshake payload the same way the site's player does:
     * AES-256-GCM with key = SHA-256(handshakeSecret), random 12-byte IV and
     * "htv-insecure-v1" as additional authenticated data. Result is a base64url
     * JSON envelope {v, alg, iv, tag, data}.
     */
    private fun encryptHandshake(payload: JSONObject): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sha256(handshakeSecret), "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(handshakeAad.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val data = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val envelope = JSONObject().apply {
            put("v", 1)
            put("alg", "AES-256-GCM")
            put("iv", base64UrlEncode(iv))
            put("tag", base64UrlEncode(tag))
            put("data", base64UrlEncode(data))
        }
        return base64UrlEncode(envelope.toString().toByteArray(Charsets.UTF_8))
    }

    /** Decrypts the x-token response header produced by the handshake endpoint. */
    private fun decryptHandshake(token: String): String {
        val envelope = JSONObject(String(base64UrlDecode(token), Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sha256(handshakeSecret), "AES"),
            GCMParameterSpec(128, base64UrlDecode(envelope.getString("iv"))),
        )
        cipher.updateAAD(handshakeAad.toByteArray(Charsets.UTF_8))
        val data = base64UrlDecode(envelope.getString("data"))
        val tag = base64UrlDecode(envelope.getString("tag"))
        return String(cipher.doFinal(data + tag), Charsets.UTF_8)
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val slug = url.substringAfterLast("/").trim()

        val timestamp = System.currentTimeMillis() / 1000L
        val payload = JSONObject().apply {
            put("timestamp_unix", timestamp)
            put("directive", "htv_player_handshake")
            put("slug", slug)
        }

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Origin", "https://hanime.tv")
            .set("Referer", "$baseUrl/")
            .add("X-Signature-Version", "web2")
            .add("X-Signature", hex(sha256("$timestamp,Xkdi29,https://hanime.tv,mn2,$timestamp")))
            .add("X-Time", timestamp.toString())
            .build()

        val body = JSONObject().apply { put("token", encryptHandshake(payload)) }
            .toString()
            .toRequestBody("application/json".toMediaType())

        return POST(handshakeUrl, apiHeaders, body)
    }

    override fun videoListSelector(): String = "video source, iframe"

    override fun videoFromElement(element: Element): Video {
        val videoUrl = element.attr("src")
        val quality = if (element.tagName() == "iframe") "Embed Server" else "720p"
        return Video(videoUrl, quality, videoUrl, headers = streamHeaders())
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()

        // The stream sources are carried in the handshake response's x-token
        // header. Consume the small response body so the connection is reused.
        response.body.string()

        val xToken = response.header("x-token")
        if (!xToken.isNullOrBlank()) {
            try {
                val sources = JSONObject(decryptHandshake(xToken)).optJSONArray("sources")
                    ?: return videoList
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src")
                    val kind = source.optString("kind")
                    // Skip preroll ads / promotions and empty sources
                    if (src.isBlank() || kind == "promotion") continue
                    val streamUrl = if (src.startsWith("http")) src else "$baseUrl$src"
                    val height = source.optInt("height", 0)
                    val quality = if (height > 0) "${height}p" else source.optString("label", "HLS")
                    videoList.add(Video(streamUrl, quality, streamUrl, headers = streamHeaders()))
                }
            } catch (_: Exception) {
            }
        }

        return videoList.distinctBy { it.videoUrl }
    }
}
