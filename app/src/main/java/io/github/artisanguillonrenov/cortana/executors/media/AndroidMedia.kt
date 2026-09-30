package io.github.artisanguillonrenov.cortana.executors.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.artisanguillonrenov.cortana.core.media.MediaException
import io.github.artisanguillonrenov.cortana.core.media.MediaProbe
import io.github.artisanguillonrenov.cortana.core.media.SpeechFileSynthesizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** MediaMetadataRetriever on a private temporary copy (never a shared file, never played). */
class AndroidMediaProbe : MediaProbe {
    private inline fun <T> with(file: File, block: (MediaMetadataRetriever) -> T): T {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(file.absolutePath); return block(r) }
        catch (e: RuntimeException) { throw MediaException("fichier multimédia illisible : ${e.message ?: "format non pris en charge"}") }
        finally { runCatching { r.release() } }
    }

    override fun info(file: File): MediaProbe.Info = with(file) { r ->
        fun s(k: Int) = r.extractMetadata(k)
        MediaProbe.Info(
            durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            width = s(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
            height = s(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
            rotation = s(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
            mime = s(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            bitrate = s(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
            hasVideo = s(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)?.let { it == "yes" },
            hasAudio = s(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let { it == "yes" },
            frameRate = s(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull(),
        )
    }

    override fun frame(file: File, timeMs: Long, maxSide: Int): Bitmap? = with(file) { r ->
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@with null
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@with null
        val f = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
        r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
    }
}

/**
 * On-device synthesis to a WAV file (TextToSpeech.synthesizeToFile) — a file for an artifact,
 * separate from the conversational voice loop, which it never drives.
 */
class AndroidSpeechFileSynthesizer(private val context: Context, private val cacheDir: File) : SpeechFileSynthesizer {
    override val id = "android-tts-file"

    override suspend fun synthesize(text: String, voice: String?, language: String): ByteArray = withTimeout(120_000) {
        var engine: TextToSpeech? = null
        try {
            val tts = suspendCancellableCoroutine<TextToSpeech> { cont ->
                var created: TextToSpeech? = null
                created = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) cont.resume(created!!) else cont.resumeWith(Result.failure(MediaException("moteur de synthèse indisponible")))
                }
                engine = created
            }
            val locale = Locale.forLanguageTag(language)
            tts.language = locale
            runCatching { tts.voices?.filter { it.locale.language == locale.language } }.getOrNull().orEmpty().let { vs ->
                (vs.firstOrNull { it.name == voice } ?: vs.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality })?.let { tts.voice = it }
            }
            val out = File(cacheDir, "tts-${System.nanoTime()}.wav")
            try {
                val id = "file-${System.nanoTime()}"
                suspendCancellableCoroutine { cont ->
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) {}
                        override fun onDone(utteranceId: String) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) { if (utteranceId == id && cont.isActive) cont.resumeWith(Result.failure(MediaException("erreur de synthèse"))) }
                    })
                    if (tts.synthesizeToFile(text, null, out, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resumeWith(Result.failure(MediaException("synthèse refusée par le moteur")))
                }
                out.readBytes().also { if (it.isEmpty()) throw MediaException("fichier audio vide") }
            } finally { out.delete() }
        } finally { engine?.shutdown() }
    }
}
