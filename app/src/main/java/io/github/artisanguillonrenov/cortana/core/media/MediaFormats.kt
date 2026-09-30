package io.github.artisanguillonrenov.cortana.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import io.github.artisanguillonrenov.cortana.core.documents.ImageSize
import io.github.artisanguillonrenov.cortana.core.vision.ScreenImage
import java.io.ByteArrayOutputStream

class MediaException(message: String) : Exception(message)

/**
 * Safe handling of image files (phase 25 gate): the type comes from the content, never from the
 * name; dimensions are read before any pixel is decoded (decompression bombs refused); decoding
 * is subsampled; anything sent to a provider is a re-encoded copy without EXIF/XMP (GPS
 * position, camera serial…), with the orientation applied.
 */
object ImageCodec {
    const val MAX_BYTES = 30_000_000
    const val MAX_PIXELS = 60_000_000L

    data class Probe(val mime: String, val width: Int, val height: Int)

    private fun at(b: ByteArray, off: Int, s: String) = b.size >= off + s.length && s.indices.all { b[off + it] == s[it].code.toByte() }

    fun mimeOf(b: ByteArray): String? = when {
        ImageSize.mime(b) != null -> ImageSize.mime(b)
        at(b, 0, "RIFF") && at(b, 8, "WEBP") -> "image/webp"
        at(b, 0, "GIF87a") || at(b, 0, "GIF89a") -> "image/gif"
        at(b, 0, "BM") && b.size > 26 -> "image/bmp"
        at(b, 4, "ftyp") && (at(b, 8, "heic") || at(b, 8, "heix") || at(b, 8, "mif1") || at(b, 8, "msf1")) -> "image/heif"
        at(b, 4, "ftyp") && (at(b, 8, "avif") || at(b, 8, "avis")) -> "image/avif"
        else -> null
    }

    fun probe(b: ByteArray): Probe {
        if (b.size > MAX_BYTES) throw MediaException("image trop volumineuse (${b.size} octets, maximum $MAX_BYTES)")
        val mime = mimeOf(b) ?: throw MediaException("ce fichier n'est pas une image reconnue (PNG, JPEG, WebP, GIF, BMP, HEIF, AVIF)")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val (w, h) = if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else ImageSize.of(b) ?: throw MediaException("dimensions de l'image illisibles")
        if (w <= 0 || h <= 0) throw MediaException("dimensions de l'image illisibles")
        if (w.toLong() * h > MAX_PIXELS) throw MediaException("image trop grande (${w}×$h pixels) : refusée (protection contre les bombes de décompression)")
        return Probe(mime, w, h)
    }

    private fun orientation(b: ByteArray): Int = runCatching {
        ExifInterface(b.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Decoded bitmap whose longest side is at most [maxSide] (subsampled while decoding), orientation applied. */
    fun decode(b: ByteArray, maxSide: Int): Bitmap {
        val p = probe(b)
        var sample = 1
        while (maxOf(p.width, p.height) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw MediaException("image illisible (${p.mime})")
        val m = Matrix()
        when (orientation(b)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val longest = maxOf(raw.width, raw.height)
        if (longest > maxSide) { val f = maxSide.toFloat() / longest; m.postScale(f, f) }
        if (m.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    fun encode(bmp: Bitmap, mime: String, quality: Int = 90): ByteArray {
        val fmt = when (mime) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/webp" -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP_LOSSLESS.takeIf { quality >= 100 } ?: Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.PNG
        }
        return ByteArrayOutputStream().also { if (!bmp.compress(fmt, quality.coerceIn(1, 100), it)) throw MediaException("encodage impossible") }.toByteArray()
    }

    /** Re-encoded copy for anything leaving the tablet: no metadata, orientation applied, longest side ≤ [maxSide]. PNG keeps transparency. */
    fun sanitize(b: ByteArray, maxSide: Int = 2048, mime: String? = null): Pair<ByteArray, String> {
        val p = probe(b)
        val out = mime ?: if (p.mime == "image/png" || p.mime == "image/gif" || p.mime == "image/webp") "image/png" else "image/jpeg"
        val bmp = decode(b, maxSide)
        try { return encode(bmp, out, 90) to out } finally { bmp.recycle() }
    }

    fun screenImage(bmp: Bitmap): ScreenImage { val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height); return ScreenImage(bmp.width, bmp.height, px) }

    /** Readable EXIF facts. The GPS position is reported as present; its coordinates only when [withLocation]. */
    fun metadata(b: ByteArray, withLocation: Boolean): List<String> = runCatching {
        val e = ExifInterface(b.inputStream())
        val out = mutableListOf<String>()
        listOf(ExifInterface.TAG_DATETIME_ORIGINAL to "Prise de vue", ExifInterface.TAG_DATETIME to "Date", ExifInterface.TAG_MAKE to "Fabricant", ExifInterface.TAG_MODEL to "Appareil",
            ExifInterface.TAG_SOFTWARE to "Logiciel", ExifInterface.TAG_IMAGE_DESCRIPTION to "Description", ExifInterface.TAG_ARTIST to "Auteur")
            .forEach { (t, label) -> e.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += "$label : ${it.take(120)}" } }
        val ll = FloatArray(2)
        @Suppress("DEPRECATION") val hasGps = e.getLatLong(ll) || e.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null
        if (hasGps) out += if (withLocation && e.getLatLong(ll)) "Position GPS : %.5f, %.5f".format(java.util.Locale.ROOT, ll[0], ll[1]) else "Position GPS présente (non affichée ; elle n'est jamais transmise à un fournisseur)"
        out
    }.getOrDefault(emptyList())
}

/** Audio files: type from the content, WAV details from the header. */
object AudioFormat {
    const val MAX_TRANSCRIBE = 25_000_000

    private fun at(b: ByteArray, off: Int, s: String) = b.size >= off + s.length && s.indices.all { b[off + it] == s[it].code.toByte() }

    fun mimeOf(b: ByteArray): String? = when {
        at(b, 0, "RIFF") && at(b, 8, "WAVE") -> "audio/wav"
        at(b, 0, "ID3") || (b.size > 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 && (b[1].toInt() and 0x06) != 0) -> "audio/mpeg"
        at(b, 0, "OggS") -> "audio/ogg"
        at(b, 0, "fLaC") -> "audio/flac"
        at(b, 4, "ftyp") && (at(b, 8, "M4A ") || at(b, 8, "M4B ")) -> "audio/mp4"
        at(b, 0, "#!AMR") -> "audio/amr"
        b.size > 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "audio/webm"
        else -> null
    }

    fun ext(mime: String) = when (mime) { "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/ogg" -> "ogg"; "audio/flac" -> "flac"; "audio/mp4" -> "m4a"; "audio/amr" -> "amr"; "audio/webm" -> "webm"; else -> "bin" }

    data class WavInfo(val sampleRate: Int, val channels: Int, val bits: Int, val durationMs: Long)

    fun wav(b: ByteArray): WavInfo? {
        if (mimeOf(b) != "audio/wav") return null
        fun le32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        fun le16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        var p = 12; var rate = 0; var ch = 0; var bits = 0; var data = -1L
        while (p + 8 <= b.size) {
            val len = le32(p + 4).toLong() and 0xffffffffL
            when {
                at(b, p, "fmt ") && p + 24 <= b.size -> { ch = le16(p + 10); rate = le32(p + 12); bits = le16(p + 22) }
                at(b, p, "data") -> { data = minOf(len, (b.size - p - 8).toLong()); break }
            }
            p += 8 + len.toInt() + (len.toInt() and 1)
            if (len > b.size) break
        }
        if (rate <= 0 || ch <= 0 || bits <= 0 || data < 0) return null
        return WavInfo(rate, ch, bits, data * 1000 / (rate.toLong() * ch * (bits / 8).coerceAtLeast(1)))
    }
}

/** Video files: type from the content. */
object VideoFormat {
    const val MAX_BYTES = 200L * 1024 * 1024
    private fun at(b: ByteArray, off: Int, s: String) = b.size >= off + s.length && s.indices.all { b[off + it] == s[it].code.toByte() }
    fun mimeOf(b: ByteArray): String? = when {
        at(b, 4, "ftyp") && at(b, 8, "qt  ") -> "video/quicktime"
        at(b, 4, "ftyp") && at(b, 8, "3gp") -> "video/3gpp"
        at(b, 4, "ftyp") && !at(b, 8, "M4A ") && !at(b, 8, "heic") && !at(b, 8, "mif1") && !at(b, 8, "avif") -> "video/mp4"
        b.size > 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/webm"
        else -> null
    }
    fun ext(mime: String) = when (mime) { "video/quicktime" -> "mov"; "video/3gpp" -> "3gp"; "video/webm" -> "webm"; else -> "mp4" }
}

/** Local video (and audio) metadata and frames — MediaMetadataRetriever on the device. */
interface MediaProbe {
    data class Info(val durationMs: Long?, val width: Int?, val height: Int?, val rotation: Int?, val mime: String?, val bitrate: Int?, val hasVideo: Boolean?, val hasAudio: Boolean?, val frameRate: Float?)
    fun info(file: java.io.File): Info
    /** Frame near [timeMs], longest side ≤ [maxSide], or null. */
    fun frame(file: java.io.File, timeMs: Long, maxSide: Int): Bitmap?
}

/** Speech to an audio file with the on-device engine (never plays anything). */
interface SpeechFileSynthesizer {
    val id: String
    /** WAV bytes. */
    suspend fun synthesize(text: String, voice: String?, language: String): ByteArray
}
