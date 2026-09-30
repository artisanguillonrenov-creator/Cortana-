package io.github.artisanguillonrenov.cortana.executors.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.view.Display
import com.googlecode.tesseract.android.TessBaseAPI
import io.github.artisanguillonrenov.cortana.core.vision.Box
import io.github.artisanguillonrenov.cortana.core.vision.CaptureUnavailable
import io.github.artisanguillonrenov.cortana.core.vision.OcrProvider
import io.github.artisanguillonrenov.cortana.core.vision.OcrResult
import io.github.artisanguillonrenov.cortana.core.vision.ScreenCapturer
import io.github.artisanguillonrenov.cortana.core.vision.ScreenImage
import io.github.artisanguillonrenov.cortana.core.vision.TextBox
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Screen capture through the AccessibilityService (`takeScreenshot`, API 30+, declared with
 * `android:canTakeScreenshot`): no MediaProjection, no persistent recording, one image on demand.
 * Protected windows (FLAG_SECURE: banking, passwords, DRM) are refused by the system and reported
 * as sensitive.
 */
class AccessibilityScreenCapturer : ScreenCapturer {
    /** Callbacks off the main thread: pixel copying never blocks the UI. */
    private val executor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "cortana-capture").apply { isDaemon = true } } }

    override suspend fun capture(): ScreenImage {
        val svc = AccessibilityBridge.service.value ?: throw CaptureUnavailable("Service d'accessibilité Cortana inactif : capture impossible.")
        repeat(3) { attempt ->
            try { return shot(svc) } catch (e: RetryLater) { delay(400L * (attempt + 1)) }
        }
        throw CaptureUnavailable("Captures trop rapprochées : réessaie dans un instant.")
    }

    private class RetryLater : Exception()

    private suspend fun shot(svc: AccessibilityService): ScreenImage = suspendCancellableCoroutine { cont ->
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    if (soft == null || soft.width <= 0 || soft.height <= 0) {
                        cont.resumeWithException(CaptureUnavailable("Capture vide renvoyée par le système."))
                        return
                    }
                    val px = IntArray(soft.width * soft.height)
                    soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
                    val img = ScreenImage(soft.width, soft.height, px)
                    soft.recycle()
                    cont.resume(img)
                } catch (t: Throwable) {
                    cont.resumeWithException(CaptureUnavailable("Capture illisible : ${t.message}"))
                } finally {
                    runCatching { buffer.close() }
                }
            }

            override fun onFailure(errorCode: Int) {
                when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> cont.resumeWithException(RetryLater())
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                        cont.resumeWithException(CaptureUnavailable("Écran protégé par l'application (contenu sécurisé) : aucune capture.", sensitive = true))
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                        cont.resumeWithException(CaptureUnavailable("Capture non autorisée : réactivez le service d'accessibilité de Cortana (droit de capture ajouté en VNext)."))
                    else -> cont.resumeWithException(CaptureUnavailable("Capture impossible (code $errorCode)."))
                }
            }
        })
    }
}

/**
 * On-device OCR with Tesseract (Apache-2.0, no network, no telemetry; D-20260927-036). Models
 * (`tessdata_fast` fra + eng) ship in the APK and are copied once to private storage after their
 * SHA-256 is checked against the pinned values.
 */
class TesseractOcrProvider(private val context: Context, private val languages: String = "fra+eng") : OcrProvider {
    override val id = "tesseract"
    private val mutex = Mutex()
    private var api: TessBaseAPI? = null
    private var broken: String? = null

    private fun prepare(): TessBaseAPI {
        api?.let { return it }
        broken?.let { throw CaptureUnavailable("OCR indisponible : $it") }
        val base = File(context.noBackupFilesDir, "ocr").apply { mkdirs() }
        val dir = File(base, "tessdata").apply { mkdirs() }
        for ((name, sha) in MODELS) {
            val f = File(dir, name)
            if (f.isFile && Hash.sha256Bytes(f.readBytes()) == sha) continue
            val bytes = context.assets.open("tessdata/$name").use { it.readBytes() }
            if (Hash.sha256Bytes(bytes) != sha) { broken = "modèle $name altéré"; throw CaptureUnavailable("OCR indisponible : modèle $name altéré (empreinte)") }
            f.writeBytes(bytes)
        }
        val t = TessBaseAPI()
        if (!t.init(base.absolutePath, languages)) { t.recycle(); broken = "initialisation"; throw CaptureUnavailable("OCR indisponible (initialisation)") }
        t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        api = t
        return t
    }

    override suspend fun recognize(img: ScreenImage): OcrResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val t0 = System.currentTimeMillis()
            val t = prepare()
            val bmp = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
            try {
                t.setImage(bmp)
                t.getUTF8Text() // runs recognition
                val out = mutableListOf<TextBox>()
                t.getResultIterator()?.let { it ->
                    try {
                        it.begin()
                        do {
                            val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                            val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) / 100.0
                            val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                            if (text.isNotEmpty() && conf > 0.3 && r != null) out += TextBox(text, Box(r.left, r.top, r.right, r.bottom), conf)
                        } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    } finally { it.delete() }
                }
                OcrResult(out, languages, "tesseract", System.currentTimeMillis() - t0)
            } catch (e: CaptureUnavailable) { throw e } catch (e: Throwable) {
                CLog.w("ocr failed", e); throw CaptureUnavailable("OCR impossible : ${e.message}")
            } finally { bmp.recycle() }
        }
    }

    companion object {
        /** Pinned tessdata_fast models (Apache-2.0), verified before first use. */
        val MODELS = mapOf(
            "fra.traineddata" to "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca",
            "eng.traineddata" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
        )
    }
}
