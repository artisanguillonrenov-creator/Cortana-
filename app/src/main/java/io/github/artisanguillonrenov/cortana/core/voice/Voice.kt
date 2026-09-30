package io.github.artisanguillonrenov.cortana.core.voice

import io.github.artisanguillonrenov.cortana.core.vision.TextMatch
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Speech-to-text engine (doc 05 §5): on-device or remote, streaming partials when it can. */
interface SttProvider {
    val id: String
    val local: Boolean
    val streaming: Boolean
    fun start(language: String, listener: SttListener)
    fun stop()
    fun cancel()
}

interface SttListener {
    fun onReady() {}
    fun onPartial(text: String) {}
    fun onFinal(text: String)
    fun onLevel(db: Float) {}
    /** [recoverable]: nothing heard / timeout — hands-free listening may simply restart. */
    fun onError(message: String, recoverable: Boolean)
}

data class VoiceInfo(val name: String, val locale: String, val local: Boolean)

/** Text-to-speech engine: utterances are queued; [TtsListener.onDone] fires per utterance. */
interface TtsProvider {
    val id: String
    val local: Boolean
    var listener: TtsListener?
    val speaking: Boolean
    fun speak(text: String, utteranceId: String)
    fun stop()
    fun voices(): List<VoiceInfo> = emptyList()
}

interface TtsListener {
    fun onStart(utteranceId: String) {}
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, message: String) = onDone(utteranceId)
}

/** A microphone that delivers 16-bit mono PCM frames (never started implicitly). */
interface AudioSource {
    val sampleRate: Int
    fun start(onFrame: (ShortArray) -> Unit)
    fun stop()
}

sealed interface VadEvent {
    data object SpeechStart : VadEvent
    data object SpeechEnd : VadEvent
}

/**
 * Energy voice-activity detector with an adaptive noise floor: speech starts after [startMs] above
 * floor + [marginDb], ends after [hangoverMs] below it. [boostDb] raises the threshold while Cortana
 * is speaking (loudspeaker echo) so barge-in needs the owner's real voice.
 */
class Vad(val sampleRate: Int = 16_000, private val startMs: Int = 60, private val hangoverMs: Int = 700, private val marginDb: Double = 12.0, private val minDb: Double = -55.0) {
    var noiseDb = -65.0
        private set
    var inSpeech = false
        private set
    var boostDb = 0.0
    private var aboveMs = 0
    private var belowMs = 0

    fun reset() { inSpeech = false; aboveMs = 0; belowMs = 0 }

    fun process(frame: ShortArray): VadEvent? {
        if (frame.isEmpty()) return null
        val db = levelDb(frame)
        val ms = frame.size * 1000 / sampleRate
        val threshold = max(noiseDb + marginDb, minDb) + boostDb
        if (!inSpeech && db < noiseDb + 6) noiseDb = 0.95 * noiseDb + 0.05 * db // adapt to the room while quiet
        if (db >= threshold) { aboveMs += ms; belowMs = 0 } else { belowMs += ms; if (!inSpeech) aboveMs = 0 }
        if (!inSpeech && aboveMs >= startMs) { inSpeech = true; belowMs = 0; return VadEvent.SpeechStart }
        if (inSpeech && belowMs >= hangoverMs) { inSpeech = false; aboveMs = 0; return VadEvent.SpeechEnd }
        return null
    }

    companion object {
        fun levelDb(frame: ShortArray): Double {
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size) / 32768.0
            return if (rms <= 1e-9) -120.0 else 20 * log10(rms)
        }
    }
}

object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val data = pcm.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data)
        for (s in pcm) le16(s.toInt())
        return out.toByteArray()
    }
}

/** Streaming TTS: model deltas become whole sentences as soon as they are complete. */
class SentenceChunker(private val minChars: Int = 24) {
    private val buf = StringBuilder()

    fun push(delta: String): List<String> {
        buf.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = boundary() ?: break
            val s = clean(buf.substring(0, cut))
            buf.delete(0, cut)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    fun flush(): String? = clean(buf.toString()).also { buf.clear() }.takeIf { it.isNotEmpty() }
    fun reset() = buf.clear()

    private fun boundary(): Int? {
        for (i in buf.indices) {
            val c = buf[i]
            val end = c == '\n' || ((c == '.' || c == '!' || c == '?' || c == '…' || c == ':' || c == ';') && (i + 1 < buf.length && buf[i + 1].isWhitespace()))
            if (end && (i + 1 >= minChars || c == '\n')) return i + 1
        }
        return null
    }

    companion object {
        /** Markdown and code are not read aloud as symbols. */
        fun clean(s: String) = s.replace(Regex("```[\\s\\S]*?```"), " (code) ").replace(Regex("[*_`#>|]"), "").replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Wake phrase recognized on transcripts (only in the explicit, visible hands-free mode — no hidden
 * listening, no extra model). Returns the command after the phrase, "" for the phrase alone, null if absent.
 */
object WakeWord {
    private val greetings = setOf("ok", "okay", "hey", "he", "hé", "dis", "salut", "bonjour", "eh")

    fun strip(transcript: String, phrase: String = "Cortana"): String? {
        val words = TextMatch.normalize(transcript).split(' ').filter { it.isNotEmpty() }
        val target = TextMatch.normalize(phrase)
        val n = target.split(' ').size
        var i = 0
        while (i < words.size && i < 2 && words[i] in greetings) i++
        if (words.size < i + n) return null
        val candidate = words.subList(i, i + n).joinToString(" ")
        if (candidate != target && TextMatch.levenshtein(candidate, target) > max(1, target.length / 4)) return null
        // Return the rest of the original text (punctuation kept), after the wake phrase.
        val original = transcript.trim().split(Regex("\\s+"))
        return original.drop(i + n).joinToString(" ").trimStart(',', ' ', '.', '!', '?').trim()
    }

    private val stop = Regex("""(?i)^\W*(arr[êe]te(?:r)? (?:d'|de |l')?[ée]cout\w*|fin (?:de l'|du mode )?(?:[ée]coute|mains libres)|d[ée]sactive (?:le mode )?mains libres|stop (?:cortana|[ée]coute))\W*$""")

    fun isStop(command: String) = stop.containsMatchIn(command.trim())
}
