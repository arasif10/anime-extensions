package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Log
import android.util.LruCache
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FlixProxyServer(
    private val headers: Headers,
    private var segmentMask: ByteArray,
) : NanoHTTPD(0) {

    fun updateSegmentMask(newMask: ByteArray) {
        if (!newMask.contentEquals(segmentMask)) {
            segmentMask = newMask
        }
    }

    // Dedicated client: 30s timeout, larger connection pool. DO NOT force HTTP/1.1 (causes 403s)
    private val proxyClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(30, 2, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Decrypted manifest cache: the remote enc-dec decryptor is slow (~5-10s per
    // cache-miss) but fast on repeats, and the same manifests get requested
    // several times (once by the extension's parse, once+ by mpv). Keyed by the
    // fully-wrapped URL (embedding the per-session token), so entries are never
    // shared across different streams.
    private val manifestCache = LruCache<String, String>(48)

    // Decoded segment bytes (fake image header stripped + XOR removed). mpv
    // re-requests the first segments after warm-up, so caching them here avoids
    // re-hitting the slow CDN. Bounded by total KB (max 24 MB).
    private val segmentCache = object : LruCache<String, ByteArray>(24 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size / 1024 + 1
    }

    fun createProxyUrl(originalUrl: String, wPayload: String): String {
        val params = "url=${URLEncoder.encode(originalUrl, "UTF-8")}&w_payload=${URLEncoder.encode(wPayload, "UTF-8")}"
        // Do not append fake extensions. MPV handles the stream better when it relies
        // on the MIME type and the HLS demuxer handles the timestamps correctly.
        return "http://127.0.0.1:$listeningPort/proxy?$params"
    }

    fun createSubtitleUrl(originalUrl: String): String {
        val ext = when {
            originalUrl.contains(".ass", ignoreCase = true) -> ".ass"
            originalUrl.contains(".vtt", ignoreCase = true) -> ".vtt"
            originalUrl.contains(".srt", ignoreCase = true) -> ".srt"
            else -> ""
        }
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://127.0.0.1:$listeningPort/sub$ext?url=$encodedUrl"
    }

    fun wrapInDecApi(originalUrl: String, wPayload: String): String {
        if (originalUrl.contains(encDecUrl)) return originalUrl
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8").replace("+", "%20")
        val encodedWPayload = URLEncoder.encode(wPayload, "UTF-8").replace("+", "%20")
        return "$decApi/parse-flixcloud?url=$encodedUrl&w_payload=$encodedWPayload"
    }

    fun ensureToken(segmentUrl: String, parentUrl: String): String = try {
        val segHttpUrl = segmentUrl.toHttpUrl()

        // Extract token from parent URL or its nested 'url' parameter if wrapped in enc-dec.app
        // If segment URL has no token, walk up to 3 levels of nested `url=` params
        // looking for one. The enc-dec.app wrapper carries the token in its top-level
        // query string, so this finds it.
        var token: String? = segHttpUrl.queryParameter("token")
        if (token == null) {
            var currentUrl = parentUrl
            repeat(3) {
                val httpUrl = currentUrl.toHttpUrl()
                if (token == null) token = httpUrl.queryParameter("token")
                if (token == null) {
                    val nestedUrl = httpUrl.queryParameter("url")
                    if (nestedUrl != null) currentUrl = nestedUrl
                }
            }
        }

        segHttpUrl.newBuilder().apply {
            if (token != null && segHttpUrl.queryParameter("token") == null) {
                addQueryParameter("token", token)
            }
        }.build().toString()
    } catch (_: Exception) {
        segmentUrl
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters
        val url = params["url"]?.firstOrNull() ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")
        val wPayload = params["w_payload"]?.firstOrNull() ?: ""

        return try {
            val isSubtitle = uri.startsWith("/sub") || url.contains(".ass", ignoreCase = true) || url.contains(".srt", ignoreCase = true) || url.contains(".vtt", ignoreCase = true)
            if (isSubtitle) {
                return serveSubtitle(url)
            }

            val isManifest = url.contains(".m3u8")
            val finalUrl = if (isManifest) wrapInDecApi(url, wPayload) else url
            Log.i("ReAnimeProxy", "classify isManifest=$isManifest")

            val proxyHeaders = headers.newBuilder()
                .set("Accept", "*/*")
                .removeAll("Origin").removeAll("Referer")
                .removeAll("Sec-Fetch-Dest").removeAll("Sec-Fetch-Mode")
                .removeAll("Sec-Fetch-Site").removeAll("Accept-Encoding")
                .apply {
                    if (url.contains(encDecUrl)) {
                        add("Origin", encDecUrl)
                        add("Referer", "$encDecUrl/")
                    } else {
                        add("Origin", flixCloudUrl)
                        add("Referer", "$flixCloudUrl/")
                        add("Sec-Fetch-Dest", "empty")
                        add("Sec-Fetch-Mode", "cors")
                        add("Sec-Fetch-Site", "same-site")
                    }
                }.build()

            if (!isManifest) {
                serveSegment(finalUrl, proxyHeaders)
            } else {
                // Cache-hit path: the decrypted/rewritten playlist is already in
                // memory, so mpv gets it instantly instead of a ~5-10s remote
                // decrypt round trip.
                manifestCache.get(finalUrl)?.let { cached ->
                    return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", cached)
                }
                serveManifest(url, finalUrl, wPayload, proxyHeaders)
            }
        } catch (e: Exception) {
            // Return 503 for timeouts so the player retries the segment instead of failing completely
            val status = if (e is java.net.SocketTimeoutException) {
                Status.SERVICE_UNAVAILABLE
            } else {
                Status.INTERNAL_ERROR
            }

            newFixedLengthResponse(status, "text/plain", e.toString())
        }
    }

    private fun serveSubtitle(url: String): Response {
        val proxyHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .removeAll("Origin").removeAll("Referer")
            .removeAll("Sec-Fetch-Dest").removeAll("Sec-Fetch-Mode")
            .removeAll("Sec-Fetch-Site").removeAll("Accept-Encoding")
            .add("Origin", flixCloudUrl)
            .add("Referer", "$flixCloudUrl/")
            .build()

        val request = Request.Builder().url(url).headers(proxyHeaders).build()
        val response = proxyClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return newFixedLengthResponse(
                Status.lookup(code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "Subtitle CDN Error: $code",
            )
        }

        val body = response.body
        val contentType = when {
            url.contains(".ass", ignoreCase = true) -> "text/x-ssa"
            url.contains(".vtt", ignoreCase = true) -> "text/vtt"
            url.contains(".srt", ignoreCase = true) -> "application/x-subrip"
            else -> response.header("Content-Type") ?: "text/plain"
        }
        val length = body.contentLength()
        val inputStream = body.byteStream()

        return if (length > 0) {
            newFixedLengthResponse(Status.OK, contentType, inputStream, length)
        } else {
            newChunkedResponse(Status.OK, contentType, inputStream)
        }
    }

    /**
     * Stream a flixcloud segment with on-the-fly XOR decoding.
     *
     * Wrap the OkHttp response body's [Source] in a
     * [FlixcloudSegmentSource] that:
     *  1. Skips the fake WebP/PNG image header (8 or 12 bytes)
     *  2. XOR-decrypts each subsequent byte with the 16-byte mask
     *
     * The transformed bytes flow directly to the player via NanoHTTPD's
     * InputStream-based response.
     */
    private fun serveSegment(
        finalUrl: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
        val t0 = System.currentTimeMillis()
        val response = proxyClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return newFixedLengthResponse(
                Status.lookup(code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "CDN Error: $code",
            )
        }
        Log.i("ReAnimeProxy", "segment headers in ${System.currentTimeMillis() - t0}ms len=${response.body.contentLength()}")

        val body = response.body
        val source = body.source()

        // Peek at the first 13 bytes (max 12-byte WebP header + 1 payload byte)
        // to determine the header type and whether XOR is needed. peek() does
        // not consume the bytes, so the FlixcloudSegmentSource still sees them.
        val headerBytes = try {
            source.peek().readByteArray(13)
        } catch (_: java.io.EOFException) {
            ByteArray(0)
        }

        val headerSize = detectHeader(headerBytes)
        val shouldXor = headerSize > 0

        // Output length = original length minus the stripped image header.
        val originalLength = body.contentLength()
        val outputLength = if (originalLength > 0 && headerSize > 0) {
            originalLength - headerSize
        } else {
            originalLength
        }

        // Wrap the upstream source with our XOR-decoding ForwardingSource.
        val xorSource = FlixcloudSegmentSource(source, segmentMask, headerSize, shouldXor)

        // Fully decode small/medium segments into memory and cache them so warm
        // re-requests (and mpv retries) skip the CDN round trip entirely.
        if (outputLength in 1..2_000_000) {
            try {
                val buffer = okio.Buffer()
                buffer.writeAll(xorSource)
                val bytes = buffer.readByteArray()
                if (bytes.size == outputLength.toInt()) {
                    segmentCache.put(finalUrl, bytes)
                    return newFixedLengthResponse(Status.OK, "video/mp2t", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
                }
            } catch (_: Exception) {
                // fall through to streaming
            }
        }

        val inputStream = xorSource.buffer().inputStream()
        return if (outputLength > 0) {
            newFixedLengthResponse(Status.OK, "video/mp2t", inputStream, outputLength)
        } else {
            newChunkedResponse(Status.OK, "video/mp2t", inputStream)
        }
    }

    /**
     * Pre-fetch the child playlists (audio + video variants) of a decrypted
     * master, then the first segments of the most promising variant, by making
     * requests through this same proxy (populating [manifestCache] and
     * [segmentCache]). Called from the extension right after the parse-time
     * master fetch, so by the time mpv asks for the same URLs they are served
     * from cache instead of paying a ~5-10s remote decrypt per playlist.
     *
     * @param childProxyUrls proxy URLs of the child playlists, as listed in the
     *   rewritten master that was already served to the extension.
     * @param budgetMs hard cap on how long to wait before giving up.
     */
    fun warmChildren(childProxyUrls: List<String>, budgetMs: Long) {
        if (childProxyUrls.isEmpty()) return
        Log.i("ReAnimeProxy", "warm start children=${childProxyUrls.size}")
        val t0 = System.currentTimeMillis()
        val deadline = t0 + budgetMs

        // Parallel-fetch the child playlists through ourselves.
        val children = childProxyUrls.take(4)
        val childPool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, children.size))
        val childFutures = children.map { url ->
            java.util.concurrent.CompletableFuture.supplyAsync({ fetchViaSelf(url) }, childPool)
        }
        val bodies = childFutures.mapNotNull { f ->
            try { f.get(remainingMs(deadline), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
        }
        childPool.shutdown()

        // Collect segment proxy URLs from the fetched playlists and warm the
        // first few (biggest playlist first = the video variant).
        val segmentUrls = bodies
            .sortedByDescending { it.length }
            .flatMap { body ->
                body.split("\n").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains(".m3u8") && it.startsWith("http://127.0.0.1") }
            }
            .distinct()
            .take(3)
        Log.i("ReAnimeProxy", "warm children done segs=${segmentUrls.size} elapsed=${System.currentTimeMillis() - t0}ms")

        if (segmentUrls.isEmpty()) return
        val segPool = java.util.concurrent.Executors.newFixedThreadPool(minOf(3, segmentUrls.size))
        val segFutures = segmentUrls.map { url ->
            java.util.concurrent.CompletableFuture.runAsync({ fetchViaSelf(url) }, segPool)
        }
        segFutures.forEach { f ->
            try { f.get(remainingMs(deadline), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        segPool.shutdown()
        Log.i("ReAnimeProxy", "warm done total=${System.currentTimeMillis() - t0}ms")
    }

    private fun remainingMs(deadline: Long): Long = (deadline - System.currentTimeMillis()).coerceIn(1000L, 60_000L)

    /** Make an HTTP request through this proxy (so results populate the caches). */
    private fun fetchViaSelf(proxyUrl: String): String? = runCatching {
        val req = Request.Builder().url(proxyUrl).build()
        proxyClient.newCall(req).execute().use { res ->
            if (res.isSuccessful) res.body.string() else null
        }
    }.getOrNull()

    private fun serveManifest(
        url: String,
        finalUrl: String,
        wPayload: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
        val t0 = System.currentTimeMillis()

        var response: okhttp3.Response? = null
        var attempt = 0
        while (response == null) {
            try {
                response = proxyClient.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                attempt++
                if (attempt >= 3) throw e
                Log.w("ReAnime", "Manifest timeout, retrying... (Attempt $attempt/3)")
            }
        }

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            response.close()
            return newFixedLengthResponse(
                Status.lookup(response.code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "Manifest Error: $errorBody",
            )
        }

        val bodyText = response.body.string()
        response.close()
        Log.i("ReAnimeProxy", "manifest fetched in ${System.currentTimeMillis() - t0}ms len=${bodyText.length}")

        val parentHttpUrl = if (url.contains(encDecUrl)) {
            url.toHttpUrl().queryParameter("url")?.toHttpUrl() ?: url.toHttpUrl()
        } else {
            url.toHttpUrl()
        }

        // Simplified parser: just resolve URLs and pass them to the proxy.
        val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@joinToString ""

            if (trimmed.startsWith("#")) {
                // Fix broken BANDWIDTH values so the player doesn't throttle the buffer
                val cleanedLine = if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    val peakBw = BANDWIDTH_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
                    val avgBw = AVERAGE_BANDWIDTH_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()

                    if (peakBw != null && peakBw < 100_000L) {
                        // Peak bandwidth is suspiciously low (< 100 Kbps)
                        val finalBw = if (avgBw != null && avgBw > 100_000L) {
                            // Use average bandwidth if it's valid
                            avgBw
                        } else {
                            // Assume the provider forgot to convert Kbps to bps
                            peakBw * 1000L
                        }
                        trimmed.replace(BANDWIDTH_REGEX, "BANDWIDTH=$finalBw")
                    } else {
                        trimmed
                    }
                } else {
                    trimmed
                }

                if (cleanedLine.contains("URI=\"")) {
                    val uri = URI_REGEX.find(cleanedLine)?.groupValues?.get(1) ?: ""
                    if (uri.isNotEmpty()) {
                        var resolvedUri = parentHttpUrl.resolve(uri).toString()
                        resolvedUri = ensureToken(resolvedUri, url)
                        val newUri = createProxyUrl(resolvedUri, wPayload)
                        cleanedLine.replace(URI_REGEX, "URI=\"$newUri\"")
                    } else {
                        cleanedLine
                    }
                } else {
                    cleanedLine
                }
            } else {
                var resolvedUrl = parentHttpUrl.resolve(trimmed).toString()
                resolvedUrl = ensureToken(resolvedUrl, url)
                createProxyUrl(resolvedUrl, wPayload)
            }
        }

        // Use application/vnd.apple.mpegurl to match the CDN exactly
        manifestCache.put(finalUrl, modifiedText)
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", modifiedText)
    }

    companion object {
        val flixCloudUrl = "https://flixcloud.cc"
        val encDecUrl = "https://enc-dec.app"
        val decApi = "$encDecUrl/api"
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private val AVERAGE_BANDWIDTH_REGEX = Regex("""AVERAGE-BANDWIDTH=(\d+)""")

        /**
         * Detect the fake image header type from the first bytes of a segment.
         *
         * Returns 12 for WebP (RIFF....WEBP), 8 for PNG signature, or 0 if
         * the data doesn't match either pattern (raw MPEG-TS passthrough).
         */
        private fun detectHeader(data: ByteArray): Int = when {
            data.size >= 12 &&
                data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> 12

            // RIFF....WEBP

            data.size >= 4 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> 8

            // PNG sig

            else -> 0
        }
    }
}

/**
 * Okio [Source] that strips a flixcloud segment's fake image header and
 * XOR-decrypts the payload on the fly.
 *
 * Data flows through in small chunks (whatever the network provides per read,
 * typically 4-16KB).
 * This lets the player start decoding as soon as the first bytes arrive.
 *
 * @param upstream    The original OkHttp response body source.
 * @param mask        The 16-byte XOR mask (repeating).
 * @param skipBytes   Number of header bytes to skip (8 for PNG, 12 for WebP, 0 for passthrough).
 * @param shouldXor   Whether to XOR-decrypt the payload (false if segment is already plaintext).
 */
private class FlixcloudSegmentSource(
    upstream: Source,
    private val mask: ByteArray,
    private val skipBytes: Int,
    private val shouldXor: Boolean,
) : ForwardingSource(upstream) {

    private var bytesSkipped = 0
    private var xorIndex = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        // Phase 1: skip the image header bytes (runs only on the first reads)
        while (bytesSkipped < skipBytes) {
            val toSkip = (skipBytes - bytesSkipped).toLong()
            val temp = Buffer()
            val skipped = super.read(temp, toSkip)
            if (skipped == -1L) return -1L
            bytesSkipped += skipped.toInt()
            // temp (header bytes) is discarded when it goes out of scope
        }

        // Phase 2: read payload, XOR if needed, write to sink.
        // OkHttp's source already returns what's available from the network
        // (typically 4-16KB chunks), so no artificial cap is needed.
        val temp = Buffer()
        val n = super.read(temp, byteCount)
        if (n == -1L) return -1L

        if (shouldXor) {
            val bytes = temp.readByteArray()
            for (i in bytes.indices) {
                bytes[i] = (bytes[i].toInt() xor mask[xorIndex and 15].toInt()).toByte()
                xorIndex++
            }
            sink.write(bytes)
        } else {
            sink.write(temp, n)
        }

        return n
    }
}
