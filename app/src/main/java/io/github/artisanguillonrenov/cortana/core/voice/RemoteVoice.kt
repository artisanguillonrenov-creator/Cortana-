package io.github.artisanguillonrenov.cortana.core.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Remote speech-to-text (Whisper-compatible, through the ModelGateway): the microphone is read
 * only between [start] and the end of the utterance detected by the VAD (or a hard cap), with a
 * short pre-roll so the first syllable is kept; the audio is sent once, never stored.
 */
class RemoteSttProvider(
    private val scope: CoroutineScope,
    private val source: AudioSource,
    private val transcribe: suspend (ByteArray, String) -> String,
    private val maxUtteranceMs: Int = 30_000,
    private val noSpeechTimeoutMs: Int = 8_000,
    override val local: Boolean = false,
) : SttProvider {
    override val id = "remote-stt"
    override val streaming = false
    @Volatile private var active = false
    private var job: Job? = null

    override fun start(language: String, listener: SttListener) {
        if (active) return
        active = true
        val vad = Vad(source.sampleRate)
        val preRoll = ArrayDeque<ShortArray>()
        val speech = ArrayList<ShortArray>()
        var heardMs = 0; var waitedMs = 0
        listener.onReady()
        source.start { frame ->
            if (!active) return@start
            val ms = frame.size * 1000 / source.sampleRate
            listener.onLevel(Vad.levelDb(frame).toFloat())
            when (vad.process(frame)) {
                VadEvent.SpeechStart -> { speech.addAll(preRoll); preRoll.clear() }
                VadEvent.SpeechEnd -> { speech += frame; finish(language, speech, listener); return@start }
                null -> {}
            }
            if (vad.inSpeech || speech.isNotEmpty()) {
                speech += frame; heardMs += ms
                if (heardMs >= maxUtteranceMs) finish(language, speech, listener)
            } else {
                preRoll.addLast(frame); if (preRoll.size > 15) preRoll.removeFirst()
                waitedMs += ms
                if (waitedMs >= noSpeechTimeoutMs) { stopMic(); listener.onError("Rien entendu", recoverable = true) }
            }
        }
    }

    private fun finish(language: String, frames: List<ShortArray>, listener: SttListener) {
        if (!active) return
        stopMic()
        val pcm = ShortArray(frames.sumOf { it.size }).also { out -> var i = 0; frames.forEach { f -> f.copyInto(out, i); i += f.size } }
        job = scope.launch {
            try {
                val text = transcribe(Wav.encode(pcm, source.sampleRate), language)
                if (text.isBlank()) listener.onError("Rien compris", recoverable = true) else listener.onFinal(text)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { listener.onError(e.message ?: "transcription impossible", recoverable = false) }
        }
    }

    private fun stopMic() { if (active) { active = false; source.stop() } }
    override fun stop() = stopMic()
    override fun cancel() { stopMic(); job?.cancel() }
}

/** Plays WAV audio (AudioTrack on the device). */
interface AudioPlayer {
    suspend fun play(wav: ByteArray)
    fun stop()
}

/** Remote text-to-speech: sentences are synthesized and played in order; [stop] cuts the current one and drops the queue. */
class RemoteTtsProvider(
    private val scope: CoroutineScope,
    private val synthesize: suspend (String) -> ByteArray,
    private val player: AudioPlayer,
    override val local: Boolean = false,
) : TtsProvider {
    override val id = "remote-tts"
    override var listener: TtsListener? = null
    @Volatile override var speaking = false
        private set
    private var queue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private var worker: Job? = null

    override fun speak(text: String, utteranceId: String) {
        if (worker?.isActive != true) worker = scope.launch { drain(queue) }
        queue.trySend(text to utteranceId)
    }

    private suspend fun drain(q: Channel<Pair<String, String>>) {
        for ((text, id) in q) {
            speaking = true
            listener?.onStart(id)
            try { player.play(synthesize(text)); listener?.onDone(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { listener?.onError(id, e.message ?: "synthèse impossible") }
            finally { speaking = !q.isEmpty }
        }
    }

    override fun stop() {
        worker?.cancel(); worker = null
        queue.close(); queue = Channel(Channel.UNLIMITED)
        player.stop()
        speaking = false
    }
}
