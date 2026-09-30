package io.github.artisanguillonrenov.cortana

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.vision.Box
import io.github.artisanguillonrenov.cortana.core.vision.CaptureUnavailable
import io.github.artisanguillonrenov.cortana.core.vision.ModelVisionProvider
import io.github.artisanguillonrenov.cortana.core.vision.NodeView
import io.github.artisanguillonrenov.cortana.core.vision.OcrProvider
import io.github.artisanguillonrenov.cortana.core.vision.OcrResult
import io.github.artisanguillonrenov.cortana.core.vision.Png
import io.github.artisanguillonrenov.cortana.core.vision.ScreenCompare
import io.github.artisanguillonrenov.cortana.core.vision.ScreenContext
import io.github.artisanguillonrenov.cortana.core.vision.ScreenImage
import io.github.artisanguillonrenov.cortana.core.vision.SensitiveScreenPolicy
import io.github.artisanguillonrenov.cortana.core.vision.SensitiveText
import io.github.artisanguillonrenov.cortana.core.vision.TargetFusion
import io.github.artisanguillonrenov.cortana.core.vision.TextBox
import io.github.artisanguillonrenov.cortana.core.vision.TextMatch
import io.github.artisanguillonrenov.cortana.core.vision.VisionMode
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityScreenCapturer
import io.github.artisanguillonrenov.cortana.service.CortanaAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLegacyPath
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** VNext phase 16: screenshot + OCR + vision fallback, sensitive-screen policy, selector fusion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisionTest : CortanaTestBase() {

    @After fun unbind() { AccessibilityBridge.service.value = null; AccessibilityBridge.foregroundPackage = null }

    private fun image(w: Int, h: Int, bg: Int = WHITE, vararg rects: Pair<Box, Int>): ScreenImage {
        val px = IntArray(w * h) { bg }
        for ((b, color) in rects) for (y in b.top until b.bottom) for (x in b.left until b.right) px[y * w + x] = color
        return ScreenImage(w, h, px)
    }

    // ---------------------------------------------------------------- building blocks

    @Test fun pngEncoderProducesAStandardImage() {
        val img = image(40, 20, WHITE, Box(0, 0, 10, 10) to RED, Box(30, 10, 40, 20) to BLUE)
        val decoded = PngReader.read(Png.encode(img))
        assertEquals(40, decoded.width); assertEquals(20, decoded.height)
        assertEquals(RED and 0xFFFFFF, decoded.getRGB(5, 5) and 0xFFFFFF)
        assertEquals(BLUE and 0xFFFFFF, decoded.getRGB(35, 15) and 0xFFFFFF)
        assertEquals(WHITE and 0xFFFFFF, decoded.getRGB(20, 5) and 0xFFFFFF)
        assertTrue(Png.dataUri(img).startsWith("data:image/png;base64,iVBORw0KGgo"))
    }

    @Test fun redactionComparisonAndTolerantText() {
        val img = image(200, 100, WHITE, Box(10, 10, 60, 40) to RED)
        val red = img.redacted(listOf(Box(0, 0, 100, 50)))
        assertEquals(0xFF000000.toInt(), red.pixel(20, 20)); assertEquals(WHITE, red.pixel(150, 80)); assertEquals(RED, img.pixel(20, 20)) // original untouched
        assertFalse(ScreenCompare.diff(img, image(200, 100, WHITE, Box(10, 10, 60, 40) to RED)).changed)
        assertTrue(ScreenCompare.diff(img, image(200, 100, WHITE, Box(10, 10, 60, 40) to RED, Box(100, 20, 190, 90) to BLUE)).changed)
        assertEquals(1.0, TextMatch.score("Paramètres", "PARAMETRES"), 0.0)
        assertTrue(TextMatch.score("Valider", "Va1ider") >= 0.8)                // OCR confusion
        assertTrue(TextMatch.score("Envoyer", "Envoyer le message") >= 0.7)
        assertTrue(TextMatch.score("Valider", "Annuler") < 0.6)
        val (small, f) = image(2560, 1600).downscaled(1280)
        assertEquals(1280, small.width); assertEquals(800, small.height); assertEquals(2.0, f, 0.001)
    }

    @Test fun sensitiveTextIsFoundBeforeAnythingLeavesTheDevice() {
        assertTrue(SensitiveText.isSensitive("4111 1111 1111 1111"))            // Luhn-valid card
        assertFalse(SensitiveText.isSensitive("4111 1111 1111 1112"))           // not a card
        assertTrue(SensitiveText.isSensitive("FR76 3000 6000 0112 3456 7890 189"))
        assertTrue(SensitiveText.isSensitive("482913", "Code de vérification"))
        assertFalse(SensitiveText.isSensitive("Total 42 €")); assertFalse(SensitiveText.isSensitive("Valider"))
        val ocr = OcrResult(listOf(TextBox("Code de vérification", Box(10, 10, 300, 40), 0.9), TextBox("482913", Box(320, 10, 420, 40), 0.9), TextBox("Valider", Box(10, 100, 120, 140), 0.9)), "fra", "t")
        assertEquals(listOf(Box(320, 10, 420, 40)), SensitiveText.boxes(ocr).filter { it.left == 320 })
        assertFalse(SensitiveText.boxes(ocr).contains(Box(10, 100, 120, 140)))
    }

    @Test fun sensitiveScreenPolicy() {
        fun ctx(locked: Boolean = false, incognito: Boolean = false, sensitive: Boolean = false, allow: Boolean = false) =
            ScreenContext("com.bank.app", locked, incognito, sensitive, allow, listOf(Box(0, 0, 10, 10)))
        assertFalse(SensitiveScreenPolicy.decide(ctx(), VisionMode.OFF, false).allowed)
        assertFalse(SensitiveScreenPolicy.decide(ctx(locked = true), VisionMode.REMOTE, false).allowed)
        assertFalse(SensitiveScreenPolicy.decide(ctx(sensitive = true), VisionMode.REMOTE, false).allowed)
        SensitiveScreenPolicy.decide(ctx(sensitive = true, allow = true), VisionMode.REMOTE, false).let { assertTrue(it.allowed); assertFalse("never to a model", it.modelAllowed) }
        SensitiveScreenPolicy.decide(ctx(incognito = true), VisionMode.REMOTE, false).let { assertTrue(it.allowed); assertFalse(it.modelAllowed) }
        SensitiveScreenPolicy.decide(ctx(), VisionMode.LOCAL, false).let { assertTrue(it.allowed); assertFalse(it.modelAllowed); assertEquals(listOf(Box(0, 0, 10, 10)), it.redactions) }
        SensitiveScreenPolicy.decide(ctx(), VisionMode.REMOTE, true).let { assertTrue(it.modelAllowed); assertTrue("local model only", it.localModelOnly) }
    }

    @Test fun fusionPrefersTheAccessibilityTreeInTheDocumentedOrder() {
        fun n(i: Int, text: String? = null, desc: String? = null, id: String? = null, click: Boolean = true) = NodeView(i, text, desc, id, "Button", Box(0, i * 100, 100, i * 100 + 80), click, false, false)
        val nodes = listOf(n(0, text = "Envoyer le message"), n(1, desc = "Envoyer"), n(2, id = "com.app:id/send", text = "➤"), n(3, text = "Envoyer", click = false))
        assertEquals("resource_id", TargetFusion.fromTree("send", nodes)!!.source)
        assertEquals(3, TargetFusion.fromTree("envoyer", nodes)!!.node)          // exact text beats description
        assertEquals("description", TargetFusion.fromTree("Envoyer", nodes.filter { it.index != 3 })!!.source)
        assertEquals("text_fuzzy", TargetFusion.fromTree("Envoyer le mesage", nodes)!!.source)
        assertNull(TargetFusion.fromTree("Paramètres", nodes))
    }

    @Test fun modelVisionMapsCoordinatesBackToTheScreenAndRejectsImpossibleBoxes() = runBlocking {
        val s = session()
        c.settings.update { it.copy(visionRoute = "${s.providerId}/vision-model") }
        server.enqueue(text("""{"matches":[{"label":"⚙","x1":100,"y1":50,"x2":140,"y2":90,"confidence":0.8}]}"""))
        val v = ModelVisionProvider(c.gateway, { c.gateway.resolveRoute(null, io.github.artisanguillonrenov.cortana.core.model.RouteNeed(vision = true)) }, null)
        val m = v.locateTarget(image(2560, 1600), "icône des réglages").single()
        assertEquals(Box(200, 100, 280, 180), m.box) // ×2: the model saw a 1280×800 image
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"image_url\"") && body.contains("data:image/png;base64,") && body.contains("1280×800"))
        server.enqueue(text("""{"matches":[{"label":"x","x1":10,"y1":10,"x2":5000,"y2":20,"confidence":0.9}]}"""))
        server.enqueue(text("""{"matches":[{"label":"x","x1":10,"y1":10,"x2":5000,"y2":20,"confidence":0.9}]}"""))
        assertTrue(runCatching { v.locateTarget(image(640, 400), "x") }.exceptionOrNull() is CaptureUnavailable)
    }

    @Test fun protectedWindowsAreNeverCaptured() {
        val svc = Robolectric.setupService(CortanaAccessibilityService::class.java)
        AccessibilityBridge.service.value = svc
        shadowOf(svc).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
        val e = runCatching { runBlocking { AccessibilityScreenCapturer().capture() } }.exceptionOrNull()
        assertTrue(e.toString(), e is CaptureUnavailable && e.sensitive)
    }

    // ---------------------------------------------------------------- gate: automation with an insufficient accessibility tree

    /** A canvas-drawn app: its accessibility tree has no text, ids or descriptions — only pixels. */
    private inner class CanvasApp(val withPassword: Boolean = false, val reactive: Boolean = true, val label: (Int) -> String = { "Valider" }) {
        @Volatile var state = "form"
        private val ocrCalls = java.util.concurrent.atomic.AtomicInteger()
        val button = Box(340, 1500, 740, 1640)
        val icon = Box(900, 100, 1000, 200)
        val password = Box(100, 600, 980, 700)
        val card = Box(100, 800, 700, 860)
        val taps = CopyOnWriteArrayList<Pair<Int, Int>>()
        val ocrInputs = CopyOnWriteArrayList<ScreenImage>()

        fun screen(): ScreenImage = if (state == "form")
            image(1080, 1920, WHITE, Box(0, 0, 1080, 90) to GRAY, button to BLUE, icon to ORANGE, password to RED, card to LIGHT)
        else image(1080, 1920, WHITE, Box(0, 0, 1080, 90) to GRAY, Box(100, 300, 980, 900) to GREEN)

        val ocr = object : OcrProvider {
            override val id = "fixture-ocr"
            override suspend fun recognize(img: ScreenImage): OcrResult {
                ocrInputs += img
                val blocks = if (state == "form") listOf(TextBox("Formulaire de test", Box(40, 20, 600, 70), 0.93), TextBox(label(ocrCalls.incrementAndGet()), Box(420, 1545, 660, 1600), 0.91),
                    TextBox("4111 1111 1111 1111", card, 0.9))
                else listOf(TextBox(if (tappedIcon()) "Réglages" else "Envoyé ✓", Box(300, 550, 780, 620), 0.95))
                return OcrResult(blocks, "fra+eng", "fixture")
            }
        }

        fun tappedIcon() = taps.any { (x, y) -> icon.contains(x, y) }

        fun install(svc: CortanaAccessibilityService) {
            val root = AccessibilityNodeInfo.obtain().apply {
                setPackageName("com.example.canvas"); setClassName("android.widget.FrameLayout"); setBoundsInScreen(Rect(0, 0, 1080, 1920)); setVisibleToUser(true)
            }
            val canvas = AccessibilityNodeInfo.obtain().apply {
                setPackageName("com.example.canvas"); setClassName("com.example.CanvasView"); setBoundsInScreen(Rect(0, 90, 1080, 1920)); setVisibleToUser(true)
            }
            shadowOf(root).addChild(canvas)
            if (withPassword) shadowOf(root).addChild(AccessibilityNodeInfo.obtain().apply {
                setPackageName("com.example.canvas"); setClassName("android.widget.EditText"); setBoundsInScreen(Rect(password.left, password.top, password.right, password.bottom))
                setVisibleToUser(true); setEditable(true); setPassword(true)
            })
            shadowOf(svc).setRootInActiveWindow(root)
            shadowOf(svc).setCanDispatchGestures(true)
            AccessibilityBridge.service.value = svc
            AccessibilityBridge.foregroundPackage = "com.example.canvas"
            c.screenCapturer = io.github.artisanguillonrenov.cortana.core.vision.ScreenCapturer { screen() }
            c.ocrProvider = ocr
        }

        /** The "system": completes dispatched gestures and lets the app react to a tap on its button or icon. */
        fun react(svc: CortanaAccessibilityService, running: AtomicBoolean) = Thread {
            var seen = 0
            while (running.get()) {
                val all = shadowOf(svc).gesturesDispatched
                while (seen < all.size) {
                    val d = all[seen++]
                    val p = Shadow.extract<ShadowLegacyPath>(d.description().getStroke(0).path).points.first()
                    val (x, y) = p.x.toInt() to p.y.toInt()
                    taps += x to y
                    if (reactive && (button.contains(x, y) || icon.contains(x, y))) state = "done"
                    d.callback()?.onCompleted(d.description())
                }
                Thread.sleep(10)
            }
        }.also { it.start() }
    }

    private fun runUi(svc: CortanaAccessibilityService, app: CanvasApp, prompt: String) {
        val running = AtomicBoolean(true)
        val system = app.react(svc, running)
        val approver = Thread { while (running.get()) { c.approvals.pending.value?.let { c.approvals.resolve(it.id, ApprovalDecision(true)) }; Thread.sleep(20) } }.also { it.start() }
        try {
            check(c.orchestrator.submit(session().id, prompt))
            val deadline = System.currentTimeMillis() + 60_000
            while (c.orchestrator.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(40)
            assertFalse("task did not finish", c.orchestrator.isBusy())
        } finally { running.set(false); system.join(); approver.join() }
    }

    @Test fun automationSucceedsOnAScreenWhoseAccessibilityTreeIsInsufficient() {
        val svc = Robolectric.setupService(CortanaAccessibilityService::class.java)
        val app = CanvasApp().apply { install(svc) }
        server.enqueue(toolCall("android_ui_observe", "{}", id = "v1"))
        server.enqueue(toolCall("android_ui_look", "{}", id = "v2"))
        server.enqueue(toolCall("android_ui_click", """{"target":"Valider","expect_text":"Envoyé"}""", id = "v3"))
        server.enqueue(text("Formulaire validé."))
        runUi(svc, app, "Valide le formulaire affiché à l'écran.")
        val t = lastTask()
        val calls = runBlocking { c.db.tasks().toolCalls(t.id) }
        assertEquals(listOf("android.ui.observe", "android.ui.look", "android.ui.click"), calls.map { it.capability })
        assertTrue(calls.all { it.outcome == "ok" })
        assertTrue(calls[0].outputRef!!, calls[0].outputRef!!.contains("android_ui_look"))                     // the tree says it is not enough
        assertTrue(calls[1].outputRef!!.contains("« Valider »") && calls[1].outputRef!!.contains("OCR sur l'appareil"))
        val click = calls[2].outputRef!!
        assertTrue(click, click.contains("lecture visuelle locale (OCR)") && click.contains("Vérifié : l'écran a changé, « Envoyé » visible"))
        val (x, y) = app.taps.single()
        assertTrue("tap ($x,$y) inside the button", app.button.contains(x, y))
        assertEquals("done", app.state)
        assertTrue("local mode: no image ever sent to a model", requestBodies(4).none { it.contains("image_url") })
    }

    @Test fun visionModelFindsAnIconOnAMaskedCaptureWhenOcrCannot() {
        val svc = Robolectric.setupService(CortanaAccessibilityService::class.java)
        val app = CanvasApp(withPassword = true).apply { install(svc) }
        val s = session()
        runBlocking { c.settings.update { it.copy(visionFallback = "remote", visionRoute = "${s.providerId}/vision-model") } }
        server.enqueue(toolCall("android_ui_click", """{"target":"icône engrenage des réglages","expect_text":"Réglages"}""", id = "w1"))
        // The model sees a 720×1280 image (1920 → 1280): the icon (900,100)-(1000,200) is at (600,66)-(666,133).
        server.enqueue(text("""{"matches":[{"label":"engrenage","x1":600,"y1":66,"x2":666,"y2":133,"confidence":0.84}]}"""))
        server.enqueue(text("Réglages ouverts."))
        runUi(svc, app, "Touche l'engrenage de l'application affichée à l'écran.")
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals(call.outputRef, "ok", call.outcome)
        assertTrue(call.outputRef!!.contains("modèle de vision") && call.outputRef!!.contains("« Réglages » visible"))
        assertTrue(app.tappedIcon())
        // Password field blacked out before OCR; password and card number blacked out in what the model saw.
        val ocrSaw = app.ocrInputs.first()
        assertEquals(0xFF000000.toInt(), ocrSaw.pixel(app.password.centerX, app.password.centerY))
        val body = requestBodies(3)[1]
        val b64 = Regex("data:image/png;base64,([A-Za-z0-9+/=]+)").find(body)!!.groupValues[1]
        val sent = PngReader.read(java.util.Base64.getDecoder().decode(b64))
        assertEquals(720, sent.width); assertEquals(1280, sent.height)
        fun at(b: Box) = sent.getRGB((b.centerX / 1.5).toInt(), (b.centerY / 1.5).toInt()) and 0xFFFFFF
        assertEquals("password masked", 0, at(app.password))
        assertEquals("card number masked", 0, at(app.card))
        assertEquals("the rest is visible", ORANGE and 0xFFFFFF, at(app.icon))
    }

    @Test fun sensitiveAppsAreNeitherCapturedNorAutomated() {
        val svc = Robolectric.setupService(CortanaAccessibilityService::class.java)
        val app = CanvasApp().apply { install(svc) }
        AccessibilityBridge.foregroundPackage = "fr.creditagricole.androidapp"
        var captured = false
        c.screenCapturer = io.github.artisanguillonrenov.cortana.core.vision.ScreenCapturer { captured = true; app.screen() }
        server.enqueue(toolCall("android_ui_look", "{}", id = "x1"))
        server.enqueue(text("Je ne peux pas regarder cette application."))
        runUi(svc, app, "Regarde l'écran.")
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals("denied", call.outcome)
        assertFalse("nothing was captured", captured)
    }

    @Test fun aCompleteAccessibilityTreeNeverTriggersACapture() = runBlocking {
        val v = io.github.artisanguillonrenov.cortana.core.vision.VisualAutomation({ throw AssertionError("no capture expected") }, null, null, { VisionMode.REMOTE }, { false })
        val nodes = listOf(NodeView(0, "Valider", null, "com.app:id/ok", "Button", Box(0, 0, 100, 50), true, false, false))
        val r = v.locate("Valider", nodes, ScreenContext("com.app", false, false, false, false, emptyList()))
        assertEquals("text", r.target!!.source); assertNull(r.captured); assertNull(v.last)
    }

    @Test fun aTargetThatBecameRiskierAfterApprovalIsRefused() {
        val svc = Robolectric.setupService(CortanaAccessibilityService::class.java)
        // Evaluated as « Valider » (L2, approved); at execution the same button reads « Valider le paiement » (L3).
        val app = CanvasApp(label = { n -> if (n == 1) "Valider" else "Valider le paiement" }).apply { install(svc) }
        server.enqueue(toolCall("android_ui_click", """{"target":"Valider"}""", id = "y1"))
        server.enqueue(text("Je n'ai pas validé : l'écran a changé."))
        runUi(svc, app, "Valide le formulaire affiché à l'écran.")
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals("error", call.outcome)
        assertTrue(call.outputRef!!, call.outputRef!!.contains("plus sensible"))
        assertTrue("nothing was tapped", app.taps.isEmpty())
    }

    @Test fun anActionWithoutVisibleEffectIsReportedAsNotConfirmed() {
        val svc = Robolectric.setupService(CortanaAccessibilityService::class.java)
        val app = CanvasApp(reactive = false).apply { install(svc) } // the tap lands, the app does not react
        server.enqueue(toolCall("android_ui_click", """{"target":"Valider","expect_text":"Envoyé"}""", id = "z1"))
        server.enqueue(text("Le bouton n'a pas réagi."))
        runUi(svc, app, "Valide le formulaire affiché à l'écran.")
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals("error", call.outcome)
        assertTrue(call.outputRef!!, call.outputRef!!.contains("non confirmée") && call.outputRef!!.contains("l'écran n'a pas changé") && call.outputRef!!.contains("« Envoyé » introuvable"))
        assertEquals(1, app.taps.size)
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt(); const val RED = 0xFFE53935.toInt(); const val BLUE = 0xFF1E88E5.toInt(); const val GRAY = 0xFF9E9E9E.toInt()
        const val GREEN = 0xFF43A047.toInt(); const val ORANGE = 0xFFFB8C00.toInt(); const val LIGHT = 0xFFEEEEEE.toInt()
    }
}

/** Test-side PNG reader following the specification (signature, CRC-checked chunks, IHDR, zlib, RGB 8-bit). */
object PngReader {
    class Image(val width: Int, val height: Int, private val rgb: IntArray) { fun getRGB(x: Int, y: Int) = rgb[y * width + x] }

    fun read(bytes: ByteArray): Image {
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        require(bytes.copyOf(8).contentEquals(sig)) { "bad signature" }
        val inp = java.io.DataInputStream(bytes.inputStream().apply { skip(8) })
        var w = 0; var h = 0; val idat = java.io.ByteArrayOutputStream()
        while (true) {
            val len = inp.readInt(); val type = ByteArray(4).also { inp.readFully(it) }; val data = ByteArray(len).also { inp.readFully(it) }
            val crc = inp.readInt()
            require(java.util.zip.CRC32().apply { update(type); update(data) }.value.toInt() == crc) { "bad CRC in ${String(type)}" }
            when (String(type)) {
                "IHDR" -> { val d = java.io.DataInputStream(data.inputStream()); w = d.readInt(); h = d.readInt(); require(d.readByte().toInt() == 8 && d.readByte().toInt() == 2) { "RGB8 expected" } }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val raw = java.util.zip.InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        require(raw.size == h * (1 + 3 * w)) { "size" }
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * (1 + 3 * w)
            require(raw[row].toInt() == 0) { "filter 0 expected" }
            for (x in 0 until w) { val i = row + 1 + 3 * x; px[y * w + x] = ((raw[i].toInt() and 0xFF) shl 16) or ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i + 2].toInt() and 0xFF) }
        }
        return Image(w, h, px)
    }
}

