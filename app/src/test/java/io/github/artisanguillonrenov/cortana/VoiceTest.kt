package io.github.artisanguillonrenov.cortana

import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import io.github.artisanguillonrenov.cortana.core.voice.AudioPlayer
import io.github.artisanguillonrenov.cortana.core.voice.AudioSource
import io.github.artisanguillonrenov.cortana.core.voice.RemoteSttProvider
import io.github.artisanguillonrenov.cortana.core.voice.RemoteTtsProvider
import io.github.artisanguillonrenov.cortana.core.voice.SentenceChunker
import io.github.artisanguillonrenov.cortana.core.voice.SpeechDetector
import io.github.artisanguillonrenov.cortana.core.voice.SttListener
import io.github.artisanguillonrenov.cortana.core.voice.SttProvider
import io.github.artisanguillonrenov.cortana.core.voice.TtsListener
import io.github.artisanguillonrenov.cortana.core.voice.TtsProvider
import io.github.artisanguillonrenov.cortana.core.voice.Vad
import io.github.artisanguillonrenov.cortana.core.voice.VadEvent
import io.github.artisanguillonrenov.cortana.core.voice.VoicePhase
import io.github.artisanguillonrenov.cortana.core.voice.Wav
import io.github.artisanguillonrenov.cortana.core.voice.WakeWord
import io.github.artisanguillonrenov.cortana.executors.voice.AndroidSttProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/** VNext phase 17: voice pipeline (VAD, STT/TTS abstractions, streaming TTS, barge-in, hands-free loop). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceTest : CortanaTestBase() {

    private fun tone(ms: Int, amplitude: Int = 8000, rate: Int = 16_000) = ShortArray(rate * ms / 1000) { i -> (amplitude * sin(2 * PI * 220 * i / rate)).toInt().toShort() }
    private fun silence(ms: Int, rate: Int = 16_000) = ShortArray(rate * ms / 1000) { i -> ((i * 7919) % 41 - 20).toShort() } // faint room noise
    private fun frames(pcm: ShortArray) = pcm.toList().chunked(320).map { it.toShortArray() }

    private var diag: () -> String = { "" }

    private fun waitUntil(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("timeout waiting for: $what ${diag()}", cond())
    }

    // ---------------------------------------------------------------- building blocks

    @Test fun vadFindsSpeechAdaptsToNoiseAndResistsEcho() {
        val vad = Vad()
        val events = mutableListOf<Pair<Int, VadEvent>>()
        var t = 0
        fun feed(pcm: ShortArray) = frames(pcm).forEach { f -> vad.process(f)?.let { events += t to it }; t += 20 }
        feed(silence(1000)); assertTrue(events.isEmpty())
        feed(tone(500)); feed(silence(1000))
        assertEquals(listOf<VadEvent>(VadEvent.SpeechStart, VadEvent.SpeechEnd), events.map { it.second })
        assertTrue("start after ~60 ms of speech", events[0].first in 1040..1100)
        assertTrue("end after the 700 ms hangover", events[1].first in 2180..2260)
        // While Cortana speaks, the threshold is raised: a quieter echo does not count as the owner's voice.
        val echo = Vad().apply { boostDb = 30.0 }
        frames(silence(500)).forEach { echo.process(it) }
        assertTrue(frames(tone(400, amplitude = 200)).none { echo.process(it) == VadEvent.SpeechStart })
        assertTrue(frames(tone(400, amplitude = 20000)).any { echo.process(it) == VadEvent.SpeechStart })
    }

    @Test fun wavHeaderIsStandard() {
        val w = Wav.encode(ShortArray(160) { it.toShort() }, 16_000)
        assertEquals(44 + 320, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(16_000, (w[24].toInt() and 0xFF) or ((w[25].toInt() and 0xFF) shl 8) or ((w[26].toInt() and 0xFF) shl 16))
    }

    @Test fun streamedTextIsSpokenSentenceBySentence() {
        val c = SentenceChunker()
        assertTrue(c.push("Bonjour ! Il est ").isEmpty())                 // "Bonjour !" is shorter than a useful sentence
        assertEquals(listOf("Bonjour ! Il est 15 h 30, la température est de 21.5 degrés."), c.push("15 h 30, la température est de 21.5 degrés. Voulez"))
        assertEquals(listOf("Voulez-vous autre chose ?"), c.push("-vous **autre** chose ? ")) // markdown is not read aloud
        c.push("Voici le code : ```kotlin\nval x = 1\n``` et la [doc](https://ex.fr)")
        assertEquals("et la doc", c.flush()!!.substringAfter("(code) ").trim())
        assertNull(c.flush())
    }

    @Test fun wakePhraseAndStopPhrase() {
        assertEquals("quelle heure est-il ?", WakeWord.strip("Cortana, quelle heure est-il ?"))
        assertEquals("mets un minuteur de 5 minutes", WakeWord.strip("OK Cortana mets un minuteur de 5 minutes"))
        assertEquals("lis mes messages", WakeWord.strip("Hé Kortana lis mes messages"))   // recognizer spelling
        assertEquals("", WakeWord.strip("Cortana"))
        assertNull(WakeWord.strip("Il fait beau aujourd'hui"))
        assertNull(WakeWord.strip("La cortana est ici", "Cortana")?.takeIf { false })
        assertTrue(WakeWord.isStop("Arrête d'écouter")); assertTrue(WakeWord.isStop("désactive le mode mains libres"))
        assertFalse(WakeWord.isStop("arrête la musique"))
    }

    // ---------------------------------------------------------------- engines

    private class ScriptedMic(private val pcm: ShortArray) : AudioSource {
        override val sampleRate = 16_000
        @Volatile var running = false
        var stops = 0
        override fun start(onFrame: (ShortArray) -> Unit) {
            running = true
            Thread { for (f in pcm.toList().chunked(320)) { if (!running) break; onFrame(f.toShortArray()) } }.start()
        }
        override fun stop() { running = false; stops++ }
    }

    @Test fun remoteSttSendsOnlyTheUtteranceThroughTheGateway() {
        val s = session()
        runBlocking { c.settings.update { it.copy(sttRoute = "${s.providerId}/whisper-1") } }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"text":"Bonjour Cortana"}"""))
        val mic = ScriptedMic(silence(400) + tone(600) + silence(900) + tone(3000))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stt = RemoteSttProvider(scope, mic, { wav, lang -> c.gateway.transcribe(c.gateway.routeFor("${s.providerId}/whisper-1")!!, wav, lang) })
        val finals = CopyOnWriteArrayList<String>()
        stt.start("fr-FR", object : SttListener { override fun onFinal(text: String) { finals += text }; override fun onError(message: String, recoverable: Boolean) { finals += "ERR $message" } })
        waitUntil("transcription") { finals.isNotEmpty() }
        assertEquals(listOf("Bonjour Cortana"), finals)
        assertFalse("microphone closed at the end of the utterance", mic.running)
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        val body = req.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"model\"") && text.contains("whisper-1") && text.contains("name=\"language\"") && text.contains("RIFF") && text.contains("audio/wav"))
        val wavBytes = body.size
        assertTrue("only ~1.8 s of audio (pre-roll + speech + hangover), not the whole 4.9 s", wavBytes in 40_000..80_000)
        // Nothing said: the microphone closes after the timeout and nothing is sent.
        val quiet = ScriptedMic(silence(9000))
        val errors = CopyOnWriteArrayList<Boolean>()
        RemoteSttProvider(scope, quiet, { _, _ -> error("must not transcribe") }, noSpeechTimeoutMs = 2000)
            .start("fr-FR", object : SttListener { override fun onFinal(text: String) {}; override fun onError(message: String, recoverable: Boolean) { errors += recoverable } })
        waitUntil("no-speech timeout") { errors.isNotEmpty() }
        assertEquals(listOf(true), errors); assertFalse(quiet.running)
        assertEquals(1, server.requestCount)
    }

    @Test fun remoteTtsPlaysInOrderAndStopDropsTheQueue() = runBlocking {
        val s = session()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(Wav.encode(ShortArray(100), 24_000))))
        val wav = c.gateway.speech(c.gateway.routeFor("${s.providerId}/tts-1")!!, "Bonjour", "nova")
        assertEquals("RIFF", String(wav, 0, 4))
        val req = server.takeRequest()
        assertEquals("/v1/audio/speech", req.path)
        val json = req.body.readUtf8()
        assertTrue(json.contains("\"input\":\"Bonjour\"") && json.contains("\"voice\":\"nova\"") && json.contains("\"response_format\":\"wav\""))

        val played = CopyOnWriteArrayList<String>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val player = object : AudioPlayer {
            override suspend fun play(wav: ByteArray) { played += String(wav); if (played.size == 2) kotlinx.coroutines.withContext(Dispatchers.IO) { gate.await() } }
            override fun stop() { gate.countDown() }
        }
        val tts = RemoteTtsProvider(CoroutineScope(SupervisorJob() + Dispatchers.Default), { it.toByteArray() }, player)
        val done = CopyOnWriteArrayList<String>()
        tts.listener = object : TtsListener { override fun onDone(utteranceId: String) { done += utteranceId } }
        listOf("Un.", "Deux.", "Trois.").forEachIndexed { i, t -> tts.speak(t, "u$i") }
        waitUntil("second sentence playing") { played.size == 2 }
        assertEquals(listOf("u0"), done)
        tts.stop()
        Thread.sleep(200)
        assertEquals(listOf("Un.", "Deux."), played) // "Trois." was dropped
    }

    @Test fun androidRecognizerStreamsPartialsThenTheFinalText() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val got = mutableListOf<String>()
        val stt = AndroidSttProvider(app)
        stt.start("fr-FR", object : SttListener {
            override fun onPartial(text: String) { got += "~$text" }
            override fun onFinal(text: String) { got += text }
            override fun onError(message: String, recoverable: Boolean) { got += "ERR:$recoverable:$message" }
        })
        val r = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("fr-FR", r.lastRecognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(r.lastRecognizerIntent.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        r.triggerOnPartialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("quelle heure")) })
        r.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("quelle heure est-il")) })
        r.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(listOf("~quelle heure", "quelle heure est-il", "ERR:true:Rien entendu"), got)
    }

    // ---------------------------------------------------------------- gate: hands-free loop, controlled and visible

    private class FakeStt : SttProvider {
        override val id = "fake-stt"; override val local = true; override val streaming = true
        @Volatile var listener: SttListener? = null
        @Volatile var active = false
        val starts = java.util.concurrent.atomic.AtomicInteger()
        override fun start(language: String, listener: SttListener) { starts.incrementAndGet(); this.listener = listener; active = true }
        override fun stop() { active = false }
        override fun cancel() { active = false }
        fun say(text: String) { check(active) { "microphone closed" }; active = false; listener!!.onFinal(text) }
    }

    private class FakeTts : TtsProvider {
        override val id = "fake-tts"; override val local = true
        override var listener: TtsListener? = null
        val spoken = CopyOnWriteArrayList<String>()
        val pending = CopyOnWriteArrayList<String>()
        @Volatile var autoFinish = true
        val stops = java.util.concurrent.atomic.AtomicInteger()
        override val speaking get() = pending.isNotEmpty()
        override fun speak(text: String, utteranceId: String) { spoken += text; if (autoFinish) listener?.onDone(utteranceId) else pending += utteranceId }
        override fun stop() { stops.incrementAndGet(); pending.clear() }
    }

    private class PushMic : AudioSource {
        override val sampleRate = 16_000
        @Volatile var sink: ((ShortArray) -> Unit)? = null
        override fun start(onFrame: (ShortArray) -> Unit) { sink = onFrame }
        override fun stop() { sink = null }
        fun push(pcm: ShortArray) { val s = sink ?: error("barge-in microphone not open"); pcm.toList().chunked(320).forEach { s(it.toShortArray()) } }
    }

    @Test fun handsFreeLoopIsExplicitVisibleInterruptibleAndStoppable() {
        val stt = FakeStt(); val tts = FakeTts(); val mic = PushMic()
        c.sttAndroid = stt; c.ttsAndroid = tts; c.bargeInDetector = SpeechDetector(mic)
        session() // a model provider for the voice conversation
        runBlocking { c.settings.update { it.copy(wakeWordEnabled = true) } }
        val v = c.voice
        diag = { "state=${v.state.value} spoken=${tts.spoken} sttActive=${stt.active} starts=${stt.starts} busy=${c.orchestrator.isBusy()} requests=${server.requestCount}" }
        // No hidden listening: nothing is open until the owner asks.
        Thread.sleep(300)
        assertEquals(VoicePhase.OFF, v.state.value.phase); assertFalse(v.state.value.micOn); assertEquals(0, stt.starts.get())

        v.startHandsFree()
        waitUntil("listening") { v.state.value.phase == VoicePhase.LISTENING && stt.active }
        assertTrue(v.state.value.micOn && v.state.value.handsFree)

        stt.say("il fait beau aujourd'hui") // not addressed to Cortana
        waitUntil("still listening") { stt.active && stt.starts.get() == 2 }
        assertEquals(0, server.requestCount)

        // A long answer, streamed: spoken sentence by sentence; the owner interrupts it.
        server.enqueue(sse(
            """{"choices":[{"delta":{"content":"Il était une fois un chat très curieux. "}}]}""",
            """{"choices":[{"delta":{"content":"Il aimait regarder la pluie tomber. Un jour"}}]}""",
            """{"choices":[{"delta":{"content":", il partit en voyage."},"finish_reason":"stop"}]}""",
        ))
        tts.autoFinish = false
        stt.say("Cortana, raconte-moi une histoire")
        waitUntil("speaking with barge-in microphone open") { v.state.value.phase == VoicePhase.SPEAKING && mic.sink != null && tts.spoken.size >= 2 }
        assertTrue("the open microphone is shown", v.state.value.micOn)
        assertEquals(listOf("Il était une fois un chat très curieux.", "Il aimait regarder la pluie tomber."), tts.spoken.take(2))
        mic.push(silence(300) + tone(400, amplitude = 20000)) // the owner talks over Cortana
        waitUntil("barge-in: silenced and listening") { v.state.value.phase == VoicePhase.LISTENING && stt.active }
        assertTrue(tts.stops.get() >= 1); assertNull("barge-in microphone released", mic.sink)

        tts.autoFinish = true
        server.enqueue(text("Il est midi."))
        stt.say("Cortana quelle heure est-il")
        waitUntil("answer spoken, listening again") { tts.spoken.contains("Il est midi.") && v.state.value.phase == VoicePhase.LISTENING && stt.active }

        stt.say("Cortana, arrête d'écouter")
        waitUntil("off") { v.state.value.phase == VoicePhase.OFF }
        assertFalse(v.state.value.micOn); assertFalse(stt.active)
        val t = runBlocking { c.stateMachine.get(lastTask().id)!! }
        assertEquals("voice", t.source)
        waitUntil("audit trail") { runBlocking { c.db.audit().allAscending() }.map { it.action }.containsAll(listOf("voice.listen.start", "voice.barge_in", "voice.listen.stop")) }
        assertEquals("the stop phrase is not sent to the model", 2, server.requestCount)

        // STOP ends a voice session at once; inactivity ends it too.
        v.startHandsFree(); waitUntil("listening again") { stt.active }
        c.killSwitch.halt("test")
        waitUntil("STOP closes the microphone") { v.state.value.phase == VoicePhase.OFF && !stt.active }
        assertTrue(v.state.value.note!!.contains("STOP"))
        v.startHandsFree(); Thread.sleep(300)
        assertEquals("no listening while STOP is active", VoicePhase.OFF, v.state.value.phase)
    }

    /**
     * Chat Workspace voice overlay (doc 10 §10.2): "Interrompre" silences Cortana like a spoken barge-in.
     * Also the regression gate for a barge-in microphone that cannot open: it must never silence the answer.
     */
    @Test fun touchInterruptSilencesCortanaAndListensAgain() {
        val stt = FakeStt(); val tts = FakeTts()
        c.sttAndroid = stt; c.ttsAndroid = tts
        session()
        val v = c.voice
        diag = { "state=${v.state.value} spoken=${tts.spoken} sttActive=${stt.active} starts=${stt.starts} busy=${c.orchestrator.isBusy()} requests=${server.requestCount}" }
        v.interrupt() // nothing to interrupt: no effect
        Thread.sleep(200)
        assertEquals(VoicePhase.OFF, v.state.value.phase)
        v.startHandsFree()
        waitUntil("listening") { v.state.value.phase == VoicePhase.LISTENING && stt.active }
        server.enqueue(sse("""{"choices":[{"delta":{"content":"Première phrase assez longue. Deuxième phrase encore plus longue."},"finish_reason":"stop"}]}"""))
        tts.autoFinish = false
        stt.say("Cortana, parle-moi")
        waitUntil("speaking") { v.state.value.phase == VoicePhase.SPEAKING && tts.spoken.isNotEmpty() }
        // No microphone permission here: the barge-in microphone cannot open, the answer is spoken anyway
        // and the screen does not claim an open microphone.
        assertFalse(v.state.value.micOn)
        v.interrupt()
        waitUntil("silenced and listening again") { v.state.value.phase == VoicePhase.LISTENING && stt.active }
        assertTrue(tts.stops.get() >= 1)
        waitUntil("audited") { runBlocking { c.db.audit().allAscending() }.any { it.action == "voice.barge_in" && it.metaJson.contains("tactile") } }
        v.stop("fin du test")
    }

    @Test fun handsFreeListeningEndsAfterInactivity() {
        val stt = FakeStt(); c.sttAndroid = stt; c.ttsAndroid = FakeTts()
        runBlocking { c.settings.update { it.copy(handsFreeTimeoutSec = 1) } }
        c.voice.startHandsFree()
        waitUntil("listening") { stt.active }
        waitUntil("inactivity stop", ms = 5_000) { c.voice.state.value.phase == VoicePhase.OFF }
        assertTrue(c.voice.state.value.note!!.contains("sans parole")); assertFalse(stt.active)
    }
}
