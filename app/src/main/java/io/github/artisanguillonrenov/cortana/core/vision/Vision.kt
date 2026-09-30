package io.github.artisanguillonrenov.cortana.core.vision

import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned rectangle in screen pixels (right/bottom exclusive). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = max(0, width).toLong() * max(0, height)
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun scaled(f: Double) = Box((left * f).roundToInt(), (top * f).roundToInt(), (right * f).roundToInt(), (bottom * f).roundToInt())
    fun clampTo(w: Int, h: Int) = Box(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
    override fun toString() = "($left,$top,$right,$bottom)"
}

/**
 * A captured screen as plain ARGB pixels: platform-independent, so redaction, comparison, PNG
 * encoding and target mapping are the same code on the tablet and in tests.
 */
class ScreenImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size == width * height) { "pixels ${argb.size} ≠ $width×$height" } }

    fun pixel(x: Int, y: Int) = argb[y * width + x]

    /** Copy with every box filled in opaque black (sensitive areas never leave this object unredacted). */
    fun redacted(boxes: List<Box>): ScreenImage {
        if (boxes.isEmpty()) return this
        val out = argb.copyOf()
        for (b in boxes.map { it.clampTo(width, height) }) for (y in b.top until b.bottom) java.util.Arrays.fill(out, y * width + b.left, y * width + b.right, 0xFF000000.toInt())
        return ScreenImage(width, height, out)
    }

    fun crop(b: Box): ScreenImage {
        val c = b.clampTo(width, height)
        val out = IntArray(c.width * c.height)
        for (y in 0 until c.height) System.arraycopy(argb, (c.top + y) * width + c.left, out, y * c.width, c.width)
        return ScreenImage(c.width, c.height, out)
    }

    /** Box-averaged downscale so the longest side is at most [maxSide]; returns the image and the factor original/scaled. */
    fun downscaled(maxSide: Int): Pair<ScreenImage, Double> {
        val longest = max(width, height)
        if (longest <= maxSide) return this to 1.0
        val f = longest.toDouble() / maxSide
        val w = max(1, (width / f).toInt()); val h = max(1, (height / f).toInt())
        return resample(w, h) to width.toDouble() / w
    }

    fun resample(w: Int, h: Int): ScreenImage {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x * width / w; val x1 = max(x0 + 1, (x + 1) * width / w)
            val y0 = y * height / h; val y1 = max(y0 + 1, (y + 1) * height / h)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) { val p = argb[yy * width + xx]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++ }
            out[y * w + x] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        }
        return ScreenImage(w, h, out)
    }

    fun luminance(x: Int, y: Int): Int { val p = pixel(x, y); return (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
}

/** Minimal dependency-free PNG encoder (RGB, 8 bits, no filter) — identical output on device and JVM. */
object Png {
    fun encode(img: ScreenImage): ByteArray {
        val raw = ByteArray(img.height * (1 + img.width * 3))
        var i = 0
        for (y in 0 until img.height) {
            raw[i++] = 0
            for (x in 0 until img.width) { val p = img.argb[y * img.width + x]; raw[i++] = (p shr 16).toByte(); raw[i++] = (p shr 8).toByte(); raw[i++] = p.toByte() }
        }
        val deflater = Deflater(6).apply { setInput(raw); finish() }
        val z = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            out.write(int(data.size)); val t = type.toByteArray(Charsets.US_ASCII); out.write(t); out.write(data)
            out.write(int(CRC32().apply { update(t); update(data) }.value.toInt()))
        }
        chunk("IHDR", int(img.width) + int(img.height) + byteArrayOf(8, 2, 0, 0, 0))
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun dataUri(img: ScreenImage) = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(encode(img))

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}

data class TextBox(val text: String, val box: Box, val confidence: Double)

data class OcrResult(val blocks: List<TextBox>, val language: String, val engine: String, val durationMs: Long = 0) {
    val fullText get() = blocks.joinToString("\n") { it.text }
    fun render(limit: Int = 80) = blocks.take(limit).joinToString("\n") { "« ${it.text} » ${it.box} confiance ${(it.confidence * 100).roundToInt()} %" }
}

/** Local OCR engine (doc 05 §4): structured text, confidence, boxes, language. */
interface OcrProvider {
    val id: String
    suspend fun recognize(img: ScreenImage): OcrResult
}

data class VisualMatch(val label: String, val box: Box, val confidence: Double, val source: String)

data class ScreenDiff(val hashDistance: Int, val changedFraction: Double) {
    val changed get() = hashDistance > 4 || changedFraction > 0.02
}

/** Vision abstraction (doc 05 §3): local (OCR-based) or model-based, by capability and privacy. */
interface VisionProvider {
    val id: String
    val local: Boolean
    suspend fun analyzeScreen(img: ScreenImage, question: String?): String
    suspend fun detectText(img: ScreenImage): OcrResult
    suspend fun locateTarget(img: ScreenImage, description: String): List<VisualMatch>
    fun compareScreens(a: ScreenImage, b: ScreenImage): ScreenDiff = ScreenCompare.diff(a, b)
    suspend fun describeRegion(img: ScreenImage, box: Box): String
}

class CaptureUnavailable(message: String, val sensitive: Boolean = false) : Exception(message)

/** Captures the current screen on demand — never continuously (doc 05 §3). */
fun interface ScreenCapturer {
    suspend fun capture(): ScreenImage
}

object ScreenCompare {
    /** 64-bit difference hash of a 9×8 grayscale thumbnail. */
    fun dHash(img: ScreenImage): Long {
        val t = img.resample(9, 8)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) h = (h shl 1) or (if (t.luminance(x, y) < t.luminance(x + 1, y)) 1L else 0L)
        return h
    }

    fun diff(a: ScreenImage, b: ScreenImage): ScreenDiff {
        val ta = a.resample(32, 32); val tb = b.resample(32, 32)
        var changed = 0
        for (y in 0 until 32) for (x in 0 until 32) if (abs(ta.luminance(x, y) - tb.luminance(x, y)) > 16) changed++
        return ScreenDiff(java.lang.Long.bitCount(dHash(a) xor dHash(b)), changed / 1024.0)
    }
}

/** Tolerant text matching for OCR/labels: accents, case, punctuation, small OCR errors. */
object TextMatch {
    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun score(target: String, candidate: String): Double {
        val t = normalize(target); val c = normalize(candidate)
        if (t.isEmpty() || c.isEmpty()) return 0.0
        if (t == c) return 1.0
        val words = c.split(' ')
        val tw = t.split(' ')
        // The target as whole words inside a longer label ("Valider" in "Valider la commande").
        if (Regex("(^| )" + Regex.escape(t) + "( |$)").containsMatchIn(c)) return 0.9 * (0.75 + 0.25 * t.length / c.length)
        val jaccard = tw.toSet().intersect(words.toSet()).size.toDouble() / tw.toSet().union(words.toSet()).size
        val edit = 1.0 - levenshtein(t, c).toDouble() / max(t.length, c.length)
        return max(jaccard * 0.85, edit * 0.95)
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}

/** Screen text that must never leave the device: card numbers (Luhn), IBAN, one-time codes. */
object SensitiveText {
    private val card = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val iban = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,4})?\b""")
    private val otpLabel = Regex("""(?i)\b(code|otp|verification|vérification|pin|cvv|cvc)\b""")
    private val otp = Regex("""^\s*\d{4,8}\s*$""")

    fun isSensitive(text: String, neighbourText: String = ""): Boolean {
        card.findAll(text).forEach { m -> if (luhn(m.value.filter(Char::isDigit))) return true }
        if (iban.containsMatchIn(text.uppercase())) return true
        if (otp.matches(text) && otpLabel.containsMatchIn(neighbourText)) return true
        return otpLabel.containsMatchIn(text) && Regex("""\b\d{4,8}\b""").containsMatchIn(text)
    }

    /** Text with card numbers, IBANs and (when a code is mentioned) one-time codes replaced — the rest stays readable. */
    fun mask(text: String): String {
        var t = card.replace(text) { m -> if (luhn(m.value.filter(Char::isDigit))) "[carte masquée]" else m.value }
        t = iban.replace(t) { "[IBAN masqué]" }
        if (otpLabel.containsMatchIn(text)) t = Regex("""(?<![\d\[])\b\d{4,8}\b""").replace(t, "[code masqué]")
        return t
    }

    /** OCR boxes to black out before anything is sent to a remote model. */
    fun boxes(ocr: OcrResult): List<Box> = ocr.blocks.withIndex().filter { (i, b) ->
        val neighbours = ocr.blocks.filterIndexed { j, o -> j != i && abs(o.box.centerY - b.box.centerY) < 3 * max(1, b.box.height) }.joinToString(" ") { it.text }
        isSensitive(b.text, neighbours)
    }.map { it.value.box }

    private fun luhn(d: String): Boolean {
        if (d.length !in 13..19) return false
        var sum = 0
        d.reversed().forEachIndexed { i, ch -> var n = ch - '0'; if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }; sum += n }
        return sum % 10 == 0
    }
}

/** Vision without a multimodal model: the on-device OCR engine answers text questions and finds textual targets. */
class LocalVisionProvider(private val ocr: OcrProvider) : VisionProvider {
    override val id = "local-ocr:${ocr.id}"
    override val local = true
    override suspend fun detectText(img: ScreenImage) = ocr.recognize(img)
    override suspend fun analyzeScreen(img: ScreenImage, question: String?) = "Texte lu à l'écran (OCR local) :\n" + detectText(img).render()
    override suspend fun locateTarget(img: ScreenImage, description: String): List<VisualMatch> =
        detectText(img).blocks.map { VisualMatch(it.text, it.box, TextMatch.score(description, it.text) * (0.5 + 0.5 * it.confidence), "ocr") }
            .filter { it.confidence >= 0.6 }.sortedByDescending { it.confidence }
    override suspend fun describeRegion(img: ScreenImage, box: Box) = ocr.recognize(img.crop(box)).fullText
}
