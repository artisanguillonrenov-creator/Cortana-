package io.github.artisanguillonrenov.cortana.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Optional voice output (§13). Lazily initialised; silently no-ops when TTS is unavailable. Speaks
 * sentence by sentence so a reading can be paused and resumed where it stopped (Chat Workspace, doc 10 §10.3).
 */
class Speaker(private val context: Context) {
    enum class Reading { IDLE, SPEAKING, PAUSED }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pendingStart = false
    @Volatile private var sentences: List<String> = emptyList()
    @Volatile private var index = 0
    @Volatile private var generation = 0
    private val _reading = MutableStateFlow(Reading.IDLE)
    /** What the chat's reading controls show. */
    val reading: StateFlow<Reading> = _reading

    /** Speech rate (0.5–2.0, 1 = normal), applied to the next sentence. */
    var rate: Float = 1f
        set(v) { field = v.coerceIn(0.5f, 2f); runCatching { tts?.setSpeechRate(field) } }

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.FRANCE
                runCatching { tts?.setSpeechRate(rate) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { advance(utteranceId) }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { advance(utteranceId) }
                })
                if (pendingStart) { pendingStart = false; play() }
            }
        }
    }

    fun speak(text: String) {
        val clean = text.replace(Regex("[*_`#>]"), "").take(3000)
        generation++
        sentences = split(clean)
        index = 0
        if (sentences.isEmpty()) { _reading.value = Reading.IDLE; return }
        _reading.value = Reading.SPEAKING
        ensure()
        if (ready) play() else pendingStart = true
    }

    /** Stops after the current position; [resume] continues from the sentence that was interrupted. */
    fun pause() {
        if (_reading.value != Reading.SPEAKING) return
        generation++
        _reading.value = Reading.PAUSED
        tts?.stop()
    }

    fun resume() {
        if (_reading.value != Reading.PAUSED) return
        generation++
        _reading.value = Reading.SPEAKING
        play()
    }

    fun stop() {
        generation++
        sentences = emptyList(); index = 0
        _reading.value = Reading.IDLE
        tts?.stop()
    }

    private fun play() {
        val t = tts ?: return
        val gen = generation
        val list = sentences
        if (index >= list.size) { _reading.value = Reading.IDLE; return }
        list.drop(index).forEachIndexed { k, s ->
            t.speak(s, if (k == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "cortana-$gen-${index + k}")
        }
    }

    private fun advance(id: String?) {
        val parts = id?.split('-') ?: return
        if (parts.size != 3 || parts[1].toIntOrNull() != generation) return
        val done = parts[2].toIntOrNull() ?: return
        index = done + 1
        if (index >= sentences.size) _reading.value = Reading.IDLE
    }

    companion object {
        /** Sentences of at most ~400 characters (a long paragraph is cut at a comma or a space). */
        fun split(text: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            fun flush() { sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }; sb.setLength(0) }
            for (ch in text) {
                sb.append(ch)
                if (ch in ".!?…\n" && sb.length > 1) flush()
                else if (sb.length >= 400) {
                    val cut = maxOf(sb.lastIndexOf(","), sb.lastIndexOf(" "))
                    if (cut > 100) { val rest = sb.substring(cut + 1); sb.setLength(cut + 1); flush(); sb.append(rest) } else flush()
                }
            }
            flush()
            return out
        }
    }
}
