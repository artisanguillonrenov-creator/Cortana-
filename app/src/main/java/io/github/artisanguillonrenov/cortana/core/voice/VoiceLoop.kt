package io.github.artisanguillonrenov.cortana.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoicePhase { OFF, LISTENING, THINKING, SPEAKING }

/** Everything the UI shows: the microphone is never on without [micOn] being true on screen. */
data class VoiceState(
    val phase: VoicePhase = VoicePhase.OFF,
    val handsFree: Boolean = false,
    val micOn: Boolean = false,
    val partial: String = "",
    val lastHeard: String? = null,
    val level: Float = -120f,
    val note: String? = null,
)

data class VoiceConfig(
    val language: String = "fr-FR",
    val wakeWord: Boolean = false,
    val wakePhrase: String = "Cortana",
    val inactivityMs: Long = 120_000,
    val bargeIn: Boolean = true,
)

/** Output of a voice task, as the orchestrator streams it. */
sealed interface VoiceOutput {
    val requestId: String?
    data class Delta(val text: String, override val requestId: String? = null) : VoiceOutput
    data class Done(val finalText: String?, override val requestId: String? = null) : VoiceOutput
}

/**
 * The hands-free / push-to-talk loop (doc 05 §5): Wake|PTT → VAD → STT → Cortana → streaming TTS,
 * with barge-in. Listening only ever starts from an explicit owner action; every state is visible
 * ([state]); STOP, a spoken stop phrase or inactivity end it. It owns no task logic: utterances go
 * to the orchestrator through [ingress] like any other request.
 */
class VoiceLoop(
    private val scope: CoroutineScope,
    private val stt: () -> SttProvider,
    private val tts: () -> TtsProvider,
    private val bargeInMic: () -> SpeechDetector?,
    /** Submits the utterance as a VOICE request; returns its request id, or null when it could not start. */
    private val ingress: suspend (String) -> String?,
    private val output: Flow<VoiceOutput>,
    private val config: () -> VoiceConfig,
    private val isHalted: () -> Boolean,
    private val audit: (String, String) -> Unit = { _, _ -> },
    /** The voice session (not each turn) holds the visible microphone foreground service. */
    private val onSessionActive: (Boolean) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state
    private val lock = Mutex()
    private var lastActivity = 0L
    private var watchdog: Job? = null
    private var chunker = SentenceChunker()
    private var utterances = 0
    private var pendingUtterances = mutableSetOf<String>()
    private var taskDone = false
    private var generation = 0
    private var currentRequest: String? = null

    /** Outputs received while the request id is not known yet (the request is still starting). */
    private val early = ArrayList<VoiceOutput>()
    private var awaitingRequest = false

    init {
        scope.launch {
            output.collect { o ->
                lock.withLock {
                    if (_state.value.phase != VoicePhase.THINKING && _state.value.phase != VoicePhase.SPEAKING) return@withLock
                    if (awaitingRequest) { if (early.size < 2000) early += o; return@withLock }
                    handle(o)
                }
            }
        }
    }

    private fun handle(o: VoiceOutput) {
        if (o.requestId == null || o.requestId != currentRequest) return // an interrupted or unrelated answer is never spoken
        when (o) {
            is VoiceOutput.Delta -> chunker.push(o.text).forEach { say(it) }
            is VoiceOutput.Done -> { (chunker.flush() ?: if (utterances == 0) o.finalText?.let { SentenceChunker.clean(it) } else null)?.let { say(it) }; taskDone = true; maybeFinishSpeaking() }
        }
    }

    /** One utterance, answered aloud, then the microphone closes. */
    fun pushToTalk() = start(handsFree = false)

    /** Explicit hands-free mode: keeps listening between answers until stopped. */
    fun startHandsFree() = start(handsFree = true)

    private fun start(handsFree: Boolean) = scope.launch {
        lock.withLock {
            if (isHalted()) { _state.value = VoiceState(note = "Autonomie arrêtée (STOP) : écoute impossible."); return@withLock }
            audit("voice.listen.start", if (handsFree) "mains libres" else "appui pour parler")
            _state.value = VoiceState(VoicePhase.OFF, handsFree = handsFree)
            lastActivity = clock()
            onSessionActive(true)
            listen()
            if (handsFree) watchdog = scope.launch { watch() }
        }
    }

    fun stop(reason: String) = scope.launch { lock.withLock { stopLocked(reason) } }

    /**
     * Touch barge-in (Chat Workspace, doc 10 §10.2): Cortana stops talking at once, like a spoken
     * interruption; in hands-free mode the microphone reopens. The rest of the answer stays in the chat.
     */
    fun interrupt() = scope.launch {
        lock.withLock {
            if (_state.value.phase != VoicePhase.SPEAKING) return@withLock
            audit("voice.barge_in", "tactile")
            generation++
            runCatching { bargeInMic()?.stop() }; runCatching { tts().stop() }
            chunker.reset(); pendingUtterances.clear(); taskDone = true
            lastActivity = clock()
            if (_state.value.handsFree && !isHalted()) listen() else { _state.value = VoiceState(); onSessionActive(false) }
        }
    }

    private fun stopLocked(reason: String) {
        if (_state.value.phase == VoicePhase.OFF && !_state.value.micOn) { _state.value = _state.value.copy(note = reason); return }
        generation++
        runCatching { stt().cancel() }
        runCatching { tts().stop() }
        runCatching { bargeInMic()?.stop() }
        watchdog?.cancel(); watchdog = null
        chunker.reset(); pendingUtterances.clear(); awaitingRequest = false; early.clear(); currentRequest = null
        _state.value = VoiceState(note = reason)
        onSessionActive(false)
        audit("voice.listen.stop", reason)
    }

    private fun listen() {
        val gen = generation
        _state.update { it.copy(phase = VoicePhase.LISTENING, micOn = true, partial = "") }
        stt().start(config().language, object : SttListener {
            override fun onPartial(text: String) { if (gen == generation) { lastActivity = clock(); _state.update { it.copy(partial = text) } } }
            override fun onLevel(db: Float) { if (gen == generation) _state.update { it.copy(level = db) } }
            override fun onFinal(text: String) { scope.launch { lock.withLock { if (gen == generation) heard(text) } } }
            override fun onError(message: String, recoverable: Boolean) { scope.launch { lock.withLock { if (gen == generation) failed(message, recoverable) } } }
        })
    }

    private suspend fun heard(text: String) {
        lastActivity = clock()
        val s = _state.value
        val cfg = config()
        val command = if (s.handsFree && cfg.wakeWord) WakeWord.strip(text, cfg.wakePhrase) else text.trim()
        if (command == null) { listen(); return } // not addressed to Cortana: keep listening, nothing sent
        if (WakeWord.isStop(command)) { stopLocked("Arrêt demandé à la voix"); return }
        if (command.isBlank()) { _state.update { it.copy(micOn = false, lastHeard = text) }; taskDone = true; say("Oui ?"); return }
        _state.update { it.copy(phase = VoicePhase.THINKING, micOn = false, partial = "", lastHeard = command) }
        chunker = SentenceChunker(); utterances = 0; taskDone = false
        currentRequest = null; awaitingRequest = true; early.clear()
        val gen = generation
        // Submitted outside the lock (it may wait for a previous task): STOP and barge-in stay immediate.
        scope.launch {
            val id = runCatching { ingress(command) }.getOrNull()
            lock.withLock {
                if (gen != generation) return@withLock
                awaitingRequest = false; currentRequest = id
                val buffered = early.toList(); early.clear()
                if (id == null) {
                    taskDone = true
                    say("Je suis occupée par une autre tâche. Réessayez dans un instant.")
                } else buffered.forEach { handle(it) }
            }
        }
    }

    private fun failed(message: String, recoverable: Boolean) {
        val s = _state.value
        if (s.handsFree && recoverable && clock() - lastActivity < config().inactivityMs) { listen(); return }
        stopLocked(if (recoverable) "Rien entendu" else "Micro indisponible : $message")
    }

    private fun say(text: String) {
        val id = "voice-${generation}-${utterances++}"
        pendingUtterances += id
        val t = tts()
        t.listener = object : TtsListener {
            override fun onDone(utteranceId: String) { scope.launch { lock.withLock { pendingUtterances -= utteranceId; maybeFinishSpeaking() } } }
        }
        if (_state.value.phase != VoicePhase.SPEAKING) {
            _state.update { it.copy(phase = VoicePhase.SPEAKING) }
            // A microphone that cannot open (permission withdrawn, busy) must never silence the answer:
            // it is spoken without voice barge-in; the on-screen "Interrompre" still works.
            runCatching { armBargeIn() }.onFailure { _state.update { s -> s.copy(micOn = false) } }
        }
        t.speak(text, id)
    }

    private fun armBargeIn() {
        val s = _state.value
        if (!s.handsFree || !config().bargeIn) return
        val mic = bargeInMic() ?: return
        val gen = generation
        _state.update { it.copy(micOn = true) }
        mic.start {
            scope.launch {
                lock.withLock {
                    if (gen != generation || _state.value.phase != VoicePhase.SPEAKING) return@withLock
                    // The owner speaks over Cortana: silence immediately and listen (the rest of the answer stays in the chat).
                    audit("voice.barge_in", "")
                    generation++
                    mic.stop(); tts().stop(); chunker.reset(); pendingUtterances.clear(); taskDone = true
                    lastActivity = clock()
                    listen()
                }
            }
        }
    }

    private fun maybeFinishSpeaking() {
        if (_state.value.phase != VoicePhase.SPEAKING || !taskDone || pendingUtterances.isNotEmpty()) return
        runCatching { bargeInMic()?.stop() }
        _state.update { it.copy(micOn = false) }
        if (_state.value.handsFree && !isHalted()) { lastActivity = clock(); listen() }
        else { _state.value = VoiceState(note = null); onSessionActive(false); audit("voice.listen.stop", "réponse terminée") }
    }

    private suspend fun watch() {
        while (true) {
            delay(1_000)
            val stopReason = lock.withLock {
                val s = _state.value
                when {
                    s.phase == VoicePhase.OFF -> "__end"
                    isHalted() -> "Autonomie arrêtée (STOP)"
                    s.phase == VoicePhase.LISTENING && clock() - lastActivity > config().inactivityMs -> "Écoute arrêtée après ${config().inactivityMs / 1000} s sans parole"
                    else -> null
                }
            }
            if (stopReason == "__end") return
            if (stopReason != null) { lock.withLock { stopLocked(stopReason) }; return }
        }
    }
}

/** Microphone + VAD: reports the owner starting to speak (barge-in), with a raised threshold against echo. */
class SpeechDetector(private val source: AudioSource, private val echoBoostDb: Double = 10.0) {
    @Volatile private var running = false

    fun start(onSpeech: () -> Unit) {
        if (running) return
        running = true
        val vad = Vad(source.sampleRate).apply { boostDb = echoBoostDb }
        try {
            source.start { frame -> if (running && vad.process(frame) == VadEvent.SpeechStart) onSpeech() }
        } catch (t: Throwable) {
            running = false // a failed start leaves the detector ready to try again
            throw t
        }
    }

    fun stop() { if (running) { running = false; source.stop() } }
}
