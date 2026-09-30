package io.github.artisanguillonrenov.cortana.executors.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.artisanguillonrenov.cortana.core.voice.AudioPlayer
import io.github.artisanguillonrenov.cortana.core.voice.AudioSource
import io.github.artisanguillonrenov.cortana.core.voice.SttListener
import io.github.artisanguillonrenov.cortana.core.voice.SttProvider
import io.github.artisanguillonrenov.cortana.core.voice.TtsListener
import io.github.artisanguillonrenov.cortana.core.voice.TtsProvider
import io.github.artisanguillonrenov.cortana.core.voice.VoiceInfo
import io.github.artisanguillonrenov.cortana.util.CLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The system speech recognizer, on-device when Android offers it (API 31+), with streaming
 * partial results. Created and driven on the main thread, as the platform requires.
 */
class AndroidSttProvider(private val context: Context) : SttProvider {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    override val id = "android-stt"
    override val local: Boolean get() = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    override val streaming = true

    override fun start(language: String, listener: SttListener) = post {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("Autorisation du micro refusée (Paramètres → Applications → Cortana → Autorisations)", recoverable = false); return@post
        }
        val r = recognizer ?: (if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = listener.onError(describe(error), recoverable = error in RECOVERABLE)
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (t.isNullOrEmpty()) listener.onError("Rien compris", recoverable = true) else listener.onFinal(t)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    override fun stop() = post { recognizer?.stopListening() }
    override fun cancel() = post { recognizer?.cancel() }

    private fun post(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    companion object {
        private val RECOVERABLE = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        fun describe(e: Int) = when (e) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Rien compris"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Rien entendu"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisation du micro refusée"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reconnaissance vocale indisponible hors ligne"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance occupée"
            else -> "Erreur de reconnaissance ($e)"
        }
    }
}

/** 16 kHz mono microphone with echo cancellation and noise suppression when the device has them. */
class MicAudioSource(private val context: Context) : AudioSource {
    override val sampleRate = 16_000
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null

    override fun start(onFrame: (ShortArray) -> Unit) {
        if (record != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw IllegalStateException("Autorisation du micro refusée")
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate / 5 * 2))
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(r.audioSessionId)?.enabled = true }
        record = r
        r.startRecording()
        thread = Thread({
            val frame = ShortArray(sampleRate / 50) // 20 ms
            while (record === r) {
                val n = r.read(frame, 0, frame.size)
                if (n > 0) onFrame(frame.copyOf(n)) else if (n < 0) break
            }
        }, "cortana-mic").apply { isDaemon = true; start() }
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }; runCatching { r.release() }
    }
}

/** System text-to-speech with a chosen voice (on-device voices preferred) and per-utterance callbacks. */
class AndroidTtsProvider(private val context: Context, private val voiceName: () -> String?, private val language: () -> String) : TtsProvider {
    override val id = "android-tts"
    override val local: Boolean get() = tts?.voice?.isNetworkConnectionRequired == false
    override var listener: TtsListener? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<Pair<String, String>>()
    override val speaking: Boolean get() = tts?.isSpeaking == true

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { pending.forEach { (_, id) -> listener?.onError(id, "Synthèse vocale indisponible") }; pending.clear(); return@TextToSpeech }
            configure()
            pending.forEach { (t, id) -> tts?.speak(t, TextToSpeech.QUEUE_ADD, null, id) }
            pending.clear()
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { listener?.onStart(utteranceId) }
                override fun onDone(utteranceId: String) { listener?.onDone(utteranceId) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) { listener?.onError(utteranceId, "erreur de synthèse") }
                override fun onStop(utteranceId: String, interrupted: Boolean) { listener?.onDone(utteranceId) }
            })
        }
    }

    private fun configure() {
        val t = tts ?: return
        val locale = Locale.forLanguageTag(language())
        t.language = locale
        val wanted = voiceName()
        val candidates = runCatching { t.voices?.filter { it.locale.language == locale.language } }.getOrNull().orEmpty()
        (candidates.firstOrNull { it.name == wanted } ?: candidates.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality })?.let { t.voice = it }
    }

    override fun speak(text: String, utteranceId: String) {
        ensure()
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) else pending += text to utteranceId
    }

    override fun stop() { pending.clear(); tts?.stop() }

    override fun voices(): List<VoiceInfo> = runCatching {
        ensure(); tts?.voices?.map { VoiceInfo(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired) }?.sortedBy { it.name }
    }.getOrNull().orEmpty()
}

/** Plays 16-bit PCM WAV through AudioTrack (remote TTS). */
class AudioTrackPlayer : AudioPlayer {
    @Volatile private var track: AudioTrack? = null

    override suspend fun play(wav: ByteArray) {
        if (wav.size <= 44) return
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16)
        val pcm = wav.copyOfRange(44, wav.size)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            suspendCancellableCoroutine<Unit> { cont ->
                t.notificationMarkerPosition = pcm.size / 2
                t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) { if (cont.isActive) cont.resume(Unit) }
                    override fun onPeriodicNotification(track: AudioTrack?) {}
                })
                cont.invokeOnCancellation { runCatching { t.stop() } }
                t.play()
            }
        } catch (e: Exception) { CLog.w("audio playback failed", e) } finally { runCatching { t.release() }; if (track === t) track = null }
    }

    override fun stop() { runCatching { track?.stop() } }
}
