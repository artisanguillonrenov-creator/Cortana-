package io.github.artisanguillonrenov.cortana.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.core.model.await
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pictures of web results (thumbnails, full images) for the conversation. Every download goes through
 * the web client (SSRF guard on every connection and redirect hop, blocked hosts of the network policy),
 * https only ([WebResults.media]), image types only (never SVG: it is a document, not a picture), with a
 * size cap, and is decoded sampled down. Bytes are cached on disk so an old conversation shows its
 * pictures without downloading them again; the cache is bounded.
 */
class RemoteImages(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val validate: (String?) -> String? = WebResults::media,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxCacheBytes: Long = 64L * 1024 * 1024,
) {
    /** The raw picture (cached), or null when the URL is refused, unreachable, too large or not an image. */
    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val u = validate(url) ?: return@withContext null
        val f = File(cacheDir, Hash.sha256(u).take(40))
        if (f.isFile && f.length() > 0) return@withContext runCatching { f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
        val data = runCatching { download(u) }.getOrNull() ?: return@withContext null
        runCatching { cacheDir.mkdirs(); f.writeBytes(data); trim() }
        data
    }

    /** Decoded and sampled down so that neither side exceeds about [maxPx]; never the full bitmap in memory. */
    suspend fun bitmap(url: String, maxPx: Int = 720): Bitmap? {
        val data = bytes(url) ?: return null
        return withContext(Dispatchers.Default) { decode(data, maxPx) }
    }

    private suspend fun download(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif;q=0.8").get().build()
        return client.newCall(req).await().use { r ->
            if (!r.isSuccessful) return null
            val type = r.header("Content-Type").orEmpty().lowercase()
            if (!acceptedType(type)) return null
            val body = r.body ?: return null
            if (body.contentLength() > maxBytes) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }

    private fun trim() {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEach { f -> total += f.length(); if (total > maxCacheBytes) f.delete() }
    }

    companion object {
        /** Raster images only: SVG is a document (scripts, external references), never loaded. */
        fun acceptedType(contentType: String): Boolean {
            val t = contentType.substringBefore(';').trim().lowercase()
            return t.startsWith("image/") && !t.contains("svg")
        }

        fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
            var sample = 1
            while (width / sample > maxPx * 2 || height / sample > maxPx * 2) sample *= 2
            return sample
        }

        fun decode(data: ByteArray, maxPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
            return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, opts) }.getOrNull()
        }
    }
}
