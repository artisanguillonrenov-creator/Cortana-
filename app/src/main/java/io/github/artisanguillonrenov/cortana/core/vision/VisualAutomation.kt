package io.github.artisanguillonrenov.cortana.core.vision

/**
 * The one owner of screen capture for automation (doc 05 §3): capture on demand only, apply the
 * sensitive-screen policy and redaction, read text on-device, fall back to a multimodal model only
 * when allowed, resolve targets by selector fusion and verify what an action changed.
 * Captures live in memory only (the last one, for comparison) and are never written to disk.
 */
class VisualAutomation(
    private val capturer: ScreenCapturer,
    private val ocr: OcrProvider?,
    private val remote: VisionProvider?,
    private val mode: () -> VisionMode,
    private val privacyLocalOnly: () -> Boolean,
) {
    data class Captured(val image: ScreenImage, val decision: CaptureDecision, val ocr: OcrResult?, val at: Long = System.currentTimeMillis())
    data class Resolution(val target: ResolvedTarget?, val tried: List<String>, val captured: Captured?)
    data class Verification(val changed: Boolean?, val expectFound: Boolean?, val note: String) {
        val failed get() = changed == false || expectFound == false
    }

    @Volatile var last: Captured? = null
        private set

    val available get() = mode() != VisionMode.OFF

    fun decide(ctx: ScreenContext) = SensitiveScreenPolicy.decide(ctx, mode(), privacyLocalOnly())

    /** Captures (policy first, redaction before anything reads the pixels) and reads the text on-device. */
    suspend fun capture(ctx: ScreenContext, withOcr: Boolean = true): Captured {
        val d = decide(ctx)
        if (!d.allowed) throw CaptureUnavailable(d.reason ?: "Capture refusée", sensitive = true)
        val img = capturer.capture().redacted(d.redactions)
        val text = if (withOcr) ocr?.let { o -> runCatching { o.recognize(img) }.getOrNull() } else null
        return Captured(img, d, text).also { last = it }
    }

    /** Image as a remote model may see it: passwords, card numbers, IBAN and one-time codes blacked out. */
    fun forRemote(c: Captured): ScreenImage = c.image.redacted(c.ocr?.let { SensitiveText.boxes(it) }.orEmpty())

    suspend fun look(ctx: ScreenContext, question: String?): String {
        val c = capture(ctx)
        val parts = mutableListOf<String>()
        c.ocr?.let { parts += "Texte lu sur l'écran (OCR sur l'appareil, ${it.blocks.size} zones) :\n" + it.render() }
        if (c.decision.modelAllowed && remote != null && (question != null || (c.ocr?.blocks?.size ?: 0) < 3)) {
            parts += try { "Analyse du modèle de vision (capture masquée) :\n" + remote.analyzeScreen(forRemote(c), question) }
            catch (e: CaptureUnavailable) { "Modèle de vision indisponible : ${e.message}" }
        }
        if (parts.isEmpty()) parts += if (ocr == null) "Aucun lecteur de texte disponible sur l'appareil." else "Aucun texte lisible sur la capture."
        val masked = c.decision.redactions.size + (c.ocr?.let { SensitiveText.boxes(it).size } ?: 0)
        return "📸 Capture ${c.image.width}×${c.image.height} analysée" + (if (masked > 0) ", $masked zone(s) sensible(s) masquée(s)" else "") + ".\n" + parts.joinToString("\n\n")
    }

    /** Selector fusion: accessibility tree first; the screen is captured only if the tree cannot answer. */
    suspend fun locate(target: String, nodes: List<NodeView>, ctx: ScreenContext): Resolution {
        val tried = mutableListOf("arbre d'accessibilité (identifiant, texte, description)")
        TargetFusion.fromTree(target, nodes)?.let { return Resolution(it, tried, null) }
        if (!available) return Resolution(null, tried + "lecture visuelle désactivée", null)
        val c = capture(ctx)
        tried += "OCR sur l'appareil"
        c.ocr?.let { o ->
            val matches = o.blocks.map { VisualMatch(it.text, it.box, TextMatch.score(target, it.text) * (0.6 + 0.4 * it.confidence), "ocr") }
                .filter { m -> c.decision.redactions.none { it.intersects(m.box) } }.sortedByDescending { it.confidence }
            TargetFusion.fromMatches(matches, 0.7)?.let { return Resolution(it, tried, c) }
        }
        if (c.decision.modelAllowed && remote != null) {
            try {
                val matches = remote.locateTarget(forRemote(c), target)
                tried += "modèle de vision"
                TargetFusion.fromMatches(matches, 0.5)?.let { return Resolution(it, tried, c) }
            } catch (e: CaptureUnavailable) { tried += "modèle de vision indisponible (${e.message})" }
        } else tried += if (mode() == VisionMode.REMOTE) "modèle de vision non autorisé ici (incognito ou application sensible)" else "modèle de vision non activé"
        return Resolution(null, tried, c)
    }

    /** Post-action verification: did the screen change, and does the expected text appear? */
    suspend fun verify(before: Captured?, expectText: String?, treeText: String?, ctx: ScreenContext): Verification {
        val treeHas = expectText?.let { e -> treeText?.let { TextMatch.normalize(it).contains(TextMatch.normalize(e)) } }
        if (before == null && (expectText == null || treeHas == true)) {
            return Verification(null, treeHas, if (treeHas == true) "« $expectText » visible." else "")
        }
        val after = runCatching { capture(ctx) }.getOrNull() ?: return Verification(null, treeHas, "Vérification visuelle impossible après l'action.")
        val diff = before?.let { ScreenCompare.diff(it.image, after.image) }
        val found = expectText?.let { e -> treeHas == true || after.ocr?.blocks?.any { TextMatch.score(e, it.text) >= 0.8 } == true }
        val note = listOfNotNull(
            diff?.let { if (it.changed) "l'écran a changé" else "⚠️ l'écran n'a pas changé" },
            found?.let { if (it) "« $expectText » visible" else "⚠️ « $expectText » introuvable" },
        ).joinToString(", ")
        return Verification(diff?.changed, found, "Vérifié : $note.")
    }
}
