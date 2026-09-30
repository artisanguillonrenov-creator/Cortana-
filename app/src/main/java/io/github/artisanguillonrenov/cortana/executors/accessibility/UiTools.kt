package io.github.artisanguillonrenov.cortana.executors.accessibility

import android.accessibilityservice.AccessibilityService
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.UiActionKind
import io.github.artisanguillonrenov.cortana.core.policy.UiRiskClassifier
import io.github.artisanguillonrenov.cortana.core.policy.UiTarget
import io.github.artisanguillonrenov.cortana.core.tools.PolicyContext
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.core.vision.Box
import io.github.artisanguillonrenov.cortana.core.vision.CaptureUnavailable
import io.github.artisanguillonrenov.cortana.core.vision.NodeView
import io.github.artisanguillonrenov.cortana.core.vision.ResolvedTarget
import io.github.artisanguillonrenov.cortana.core.vision.ScreenContext
import io.github.artisanguillonrenov.cortana.core.vision.TargetFusion
import io.github.artisanguillonrenov.cortana.core.vision.VisualAutomation
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.long
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Registers android.ui.* / android.nav.* capabilities (§8.2) with the UI-action risk classifier (§9.2). */
class UiTools(
    private val ui: UiController,
    private val classifier: UiRiskClassifier,
    private val settings: SettingsRepository,
    /** Vision fallback (phase 16): screenshot + on-device OCR (+ optional model) when the tree is insufficient. */
    private val vision: VisualAutomation? = null,
    private val keyguardLocked: () -> Boolean = { false },
) {
    private val visualProps = arrayOf(
        "target" to S.str("Ce qu'il faut viser tel qu'on le voit à l'écran (texte ou description), si aucun sélecteur ne convient : l'arbre d'accessibilité est essayé d'abord, puis la lecture visuelle"),
        "expect_text" to S.str("Texte qui doit apparaître après l'action (vérification)"),
    )

    private fun nodes(snap: ScreenSnapshot?): List<NodeView> = snap?.nodes.orEmpty().map { n ->
        NodeView(n.index, n.text, n.desc, n.resId, n.className, Box(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom), n.clickable, n.editable, n.password)
    }

    private fun screenContext(incognito: Boolean, snap: ScreenSnapshot?): ScreenContext {
        val pkg = AccessibilityBridge.foregroundPackage ?: snap?.packageName
        return ScreenContext(pkg, keyguardLocked(), incognito, classifier.isSensitiveApp(pkg), pkg != null && pkg in settings.current.sensitiveAllowlist,
            nodes(snap).filter { it.password }.map { it.box })
    }

    /** Visual target resolution; during policy evaluation nothing leaves the device (no remote model). */
    private suspend fun resolveVisual(target: String, incognito: Boolean, allowRemote: Boolean): VisualAutomation.Resolution {
        val snap = withContext(Dispatchers.Default) { ui.observe(400) }
        val v = vision ?: return VisualAutomation.Resolution(TargetFusion.fromTree(target, nodes(snap)), listOf("arbre d'accessibilité"), null)
        return v.locate(target, nodes(snap), screenContext(incognito || !allowRemote, snap))
    }

    private fun visualTarget(t: ResolvedTarget, screenText: String?): UiTarget = UiTarget(
        packageName = AccessibilityBridge.foregroundPackage ?: ui.lastSnapshot?.packageName, text = t.label, contentDescription = null,
        resourceId = null, className = "visuel:${t.source}", screenText = screenText ?: ui.lastSnapshot?.screenText,
    )

    private val selectorProps = arrayOf(
        "node" to S.int("Numéro [n] de l'élément dans la dernière observation"),
        "resource_id" to S.str("Identifiant de ressource (ex. com.app:id/send ou send)"),
        "text" to S.str("Texte exact (ou description) de l'élément"),
        "text_contains" to S.str("Partie du texte de l'élément"),
        "text_regex" to S.str("Expression régulière sur le texte"),
        "content_description" to S.str("Description d'accessibilité (contient)"),
        "class_name" to S.str("Classe, ex. Button, EditText"),
        "package" to S.str("Paquet de l'application"),
        "index" to S.int("Rang parmi les correspondances (0 = premier)"),
    )

    private fun selector(a: JsonObject) = Selector(
        node = a.int("node"), resourceId = a.str("resource_id"), text = a.str("text"), textContains = a.str("text_contains"),
        textRegex = a.str("text_regex"), contentDescription = a.str("content_description"), className = a.str("class_name"),
        packageName = a.str("package"), index = a.int("index"),
    )

    private fun hasSelector(a: JsonObject) = !selector(a).isEmpty()

    private fun sensitiveForeground(): RiskAssessment? {
        val pkg = AccessibilityBridge.foregroundPackage ?: ui.lastSnapshot?.packageName ?: return null
        if (!classifier.isSensitiveApp(pkg)) return null
        return if (pkg !in settings.current.sensitiveAllowlist) {
            RiskAssessment(Risk.L3, listOf("Application sensible au premier plan ($pkg)"), deny = true,
                denyReason = "Automatisation interdite dans l'application sensible $pkg (banque, paiement, mots de passe…). Autorisez-la explicitement dans Réglages si vous le souhaitez.")
        } else RiskAssessment(Risk.L3, listOf("Application sensible autorisée ($pkg)"))
    }

    private fun target(n: UiNode): UiTarget = UiTarget(
        packageName = n.packageName ?: AccessibilityBridge.foregroundPackage, text = n.text, contentDescription = n.desc,
        resourceId = n.resId, className = n.className, isPassword = n.password, isEditable = n.editable,
        screenText = ui.lastSnapshot?.screenText,
    )

    private suspend fun resolveForAction(a: JsonObject, kind: UiActionKind): UiNode? = withContext(Dispatchers.Default) {
        if (!hasSelector(a) && a.str("target") != null && kind != UiActionKind.CLICK_POINT) return@withContext null
        when {
            kind == UiActionKind.CLICK_POINT -> ui.nodeAt(a.int("x") ?: 0, a.int("y") ?: 0)
            hasSelector(a) -> runCatching { ui.resolveOne(selector(a)) }.getOrNull()
            kind == UiActionKind.TYPE || kind == UiActionKind.SUBMIT || kind == UiActionKind.PASTE -> runCatching { ui.focusedInput() }.getOrNull()
            else -> null
        }
    }

    private fun classifierFor(kind: UiActionKind): suspend (JsonObject, PolicyContext) -> RiskAssessment? = { a, _ ->
        sensitiveForeground()?.takeIf { it.deny } ?: run {
            val node = resolveForAction(a, kind)
            val base = sensitiveForeground()
            val visual = if (node == null && !hasSelector(a)) a.str("target")?.let { runCatching { resolveVisual(it, incognito = true, allowRemote = false) }.getOrNull() } else null
            if (node == null && visual?.target != null) {
                val t = visual.target!!
                val r = classifier.classify(kind, visualTarget(t, visual.captured?.ocr?.fullText), settings.current.sensitiveAllowlist)
                r.copy(risk = if (base != null) Risk.max(base.risk, r.risk) else r.risk, reasons = (base?.reasons ?: emptyList()) + r.reasons + "Cible repérée par ${ResolvedTarget.SOURCES[t.source] ?: t.source}",
                    targetDescription = t.render())
            } else if (node == null) {
                RiskAssessment(base?.risk ?: Risk.L1, (base?.reasons ?: emptyList()) + "Cible non résolue à l'évaluation")
            } else {
                val r = classifier.classify(kind, target(node), settings.current.sensitiveAllowlist)
                val risk = if (base != null) Risk.max(base.risk, r.risk) else r.risk
                r.copy(risk = risk, reasons = (base?.reasons ?: emptyList()) + r.reasons, targetDescription = node.describe())
            }
        }
    }

    private val guardOnly: suspend (JsonObject, PolicyContext) -> RiskAssessment? = { _, _ -> sensitiveForeground() }

    /** Approval is bound to the evaluated target: re-classify at execution and refuse if risk rose. */
    private fun recheck(kind: UiActionKind, n: UiNode, ctx: ToolContext) {
        val r = classifier.classify(kind, target(n), settings.current.sensitiveAllowlist)
        if (r.deny) throw UiException(r.denyReason ?: "Action refusée")
        if (r.risk.level > ctx.approvedRisk.level) {
            throw UiException("L'écran a changé : la cible « ${n.describe()} » est plus sensible (${r.risk.label}) que ce qui a été autorisé. Observe à nouveau ; une nouvelle autorisation sera demandée.")
        }
    }

    /**
     * Acts on [a]'s selector, or on its visual `target` (selector fusion) — then verifies the effect:
     * screen changed and/or `expect_text` visible. A failed verification is reported as a failure.
     */
    private suspend fun act(a: JsonObject, kind: UiActionKind, ctx: ToolContext, onNode: suspend (UiNode) -> String, onPoint: suspend (ResolvedTarget) -> String): ToolResult {
        ctx.markUiAutomation()
        return try {
            val target = a.str("target")
            val expect = a.str("expect_text")
            var before: VisualAutomation.Captured? = null
            val text = if (hasSelector(a) || target == null) {
                val n = ui.resolveOne(selector(a)); recheck(kind, n, ctx); onNode(n)
            } else {
                val res = resolveVisual(target, ctx.incognito, allowRemote = true)
                val t = res.target ?: throw UiException("« $target » introuvable à l'écran. Essayé : ${res.tried.joinToString(" → ")}. Observe ou regarde l'écran (android_ui_look) puis précise la cible.")
                before = res.captured
                if (t.node != null && ui.lastSnapshot?.nodes?.getOrNull(t.node) != null) {
                    val n = ui.lastSnapshot!!.nodes[t.node]; recheck(kind, n, ctx); onNode(n) + " (repéré par ${ResolvedTarget.SOURCES[t.source]})"
                } else {
                    val r = classifier.classify(kind, visualTarget(t, res.captured?.ocr?.fullText), settings.current.sensitiveAllowlist)
                    if (r.deny) throw UiException(r.denyReason ?: "Action refusée")
                    if (r.risk.level > ctx.approvedRisk.level) throw UiException("La cible visuelle ${t.render()} est plus sensible (${r.risk.label}) que ce qui a été autorisé : une nouvelle autorisation sera demandée.")
                    onPoint(t) + " — cible ${t.render()}"
                }
            }
            val v = vision
            if (v == null || (before == null && expect == null)) return ToolResult.ok(text)
            val snap = withContext(Dispatchers.Default) { runCatching { ui.observe(400) }.getOrNull() }
            val check = v.verify(before, expect, snap?.screenText, screenContext(ctx.incognito, snap))
            if (check.failed) ToolResult.error("$text\nAction effectuée mais non confirmée : ${check.note}") else ToolResult.ok(listOf(text, check.note).filter { it.isNotBlank() }.joinToString("\n"))
        } catch (e: UiException) {
            ToolResult.error(e.message ?: "Échec")
        } catch (e: CaptureUnavailable) {
            ToolResult.error(e.message ?: "Capture impossible")
        }
    }

    private suspend fun run(ctx: ToolContext, block: suspend () -> String): ToolResult {
        ctx.markUiAutomation()
        return try {
            ToolResult.ok(block())
        } catch (e: UiException) {
            ToolResult.error(e.message ?: "Échec")
        }
    }

    private fun def(
        cap: String, desc: String, schema: JsonObject, risk: Risk, side: SideEffect, label: String,
        classifier: (suspend (JsonObject, PolicyContext) -> RiskAssessment?)?,
        exec: suspend (JsonObject, ToolContext) -> ToolResult,
    ) = ToolDefinition(cap, desc, schema, risk, side, Idempotency.NONE, DataEgress.NONE, ToolCategory.UI, maxOutputBytes = 24_000, timeoutMs = 30_000, label = label, riskClassifier = classifier, execute = exec)

    fun tools(): List<ToolDefinition> = listOf(
        def("android.ui.observe", "Lit l'écran actuel : application, éléments visibles numérotés [n] avec texte, description, id, position et actions possibles. À appeler avant et après chaque action.",
            S.obj("max_nodes" to S.int("Nombre max d'éléments (défaut 250)", 20, 400)), Risk.L0, SideEffect.NONE, "Observer l'écran", guardOnly) { a, ctx ->
            ctx.markUiAutomation()
            try {
                val snap = withContext(Dispatchers.Default) { ui.settle(1500); ui.observe(a.int("max_nodes") ?: 250) }
                val poor = snap.nodes.count { it.text != null || it.desc != null } < 3
                val hint = if (poor && vision?.available == true) "\nArbre d'accessibilité pauvre (application dessinée ou non accessible) : utilise android_ui_look pour lire l'écran, puis android_ui_click avec target." else ""
                ToolResult.ok(snap.render() + hint, "android.ui.observe:${snap.packageName}")
            } catch (e: UiException) { ToolResult.error(e.message ?: "Échec") }
        },
        def("android.ui.look", "Regarde l'écran comme une image : capture ponctuelle (zones sensibles masquées, rien n'est enregistré) et lecture du texte sur l'appareil, avec positions. " +
            "À utiliser quand android_ui_observe ne montre pas assez d'éléments. question : ce que tu cherches (utilisé par le modèle de vision s'il est activé).",
            S.obj("question" to S.str("Ce que tu cherches à l'écran (optionnel)")), Risk.L0, SideEffect.NONE, "Regarder l'écran", guardOnly) { a, ctx ->
            ctx.markUiAutomation()
            val v = vision ?: return@def ToolResult.error("Lecture visuelle indisponible")
            try {
                val snap = withContext(Dispatchers.Default) { ui.settle(1500); ui.observe(250) }
                ToolResult.ok(v.look(screenContext(ctx.incognito, snap), a.str("question")), "android.ui.look:${snap.packageName}")
            } catch (e: UiException) { ToolResult.error(e.message ?: "Échec") } catch (e: CaptureUnavailable) { ToolResult.error(e.message ?: "Capture impossible") }
        },
        def("android.ui.find", "Cherche les éléments de l'écran correspondant à un sélecteur.",
            S.obj(*selectorProps), Risk.L0, SideEffect.NONE, "Chercher un élément", guardOnly) { a, ctx ->
            ctx.markUiAutomation()
            try {
                val m = withContext(Dispatchers.Default) { ui.find(selector(a)) }
                ToolResult.ok(if (m.isEmpty()) "Aucun élément" else m.joinToString("\n") { it.line() }, "android.ui.find")
            } catch (e: UiException) { ToolResult.error(e.message ?: "Échec") }
        },
        def("android.ui.click", "Touche un élément : sélecteur (de préférence node, resource_id ou content_description) ou, si l'élément n'est pas dans l'arbre, target (texte vu à l'écran).",
            S.obj(*selectorProps, *visualProps), Risk.L1, SideEffect.REVERSIBLE, "Toucher un élément", classifierFor(UiActionKind.CLICK)) { a, ctx ->
            act(a, UiActionKind.CLICK, ctx, { n -> ui.click(n) }, { t -> ui.clickPoint(t.x, t.y, false) })
        },
        def("android.ui.long_click", "Appui long sur un élément (sélecteur ou target).",
            S.obj(*selectorProps, *visualProps), Risk.L1, SideEffect.REVERSIBLE, "Appui long", classifierFor(UiActionKind.LONG_CLICK)) { a, ctx ->
            act(a, UiActionKind.LONG_CLICK, ctx, { n -> ui.click(n, long = true) }, { t -> ui.clickPoint(t.x, t.y, true) })
        },
        def("android.ui.click_point", "Dernier recours : toucher des coordonnées écran (x, y). Préférer android_ui_click.",
            S.obj("x" to S.int("x en pixels"), "y" to S.int("y en pixels"), "long" to S.bool("Appui long"), required = listOf("x", "y")),
            Risk.L1, SideEffect.REVERSIBLE, "Toucher des coordonnées", classifierFor(UiActionKind.CLICK_POINT)) { a, ctx ->
            run(ctx) {
                ui.nodeAt(a.int("x")!!, a.int("y")!!)?.let { recheck(UiActionKind.CLICK_POINT, it, ctx) }
                ui.clickPoint(a.int("x")!!, a.int("y")!!, a.bool("long") == true)
            }
        },
        def("android.ui.type", "Saisit du texte dans un champ (sélecteur, ou le champ qui a le focus). append=true pour ajouter.",
            S.obj(*selectorProps, *visualProps, "value" to S.str("Texte à saisir"), "append" to S.bool("Ajouter au texte existant"), required = listOf("value")),
            Risk.L1, SideEffect.REVERSIBLE, "Saisir du texte", classifierFor(UiActionKind.TYPE)) { a, ctx ->
            if (!hasSelector(a) && a.str("target") != null) return@def act(a, UiActionKind.TYPE, ctx, { n -> ui.type(n, a.str("value")!!, a.bool("append") == true) }) { t ->
                ui.clickPoint(t.x, t.y, false)
                val f = ui.focusedInput() ?: throw UiException("Aucun champ n'a pris le focus après avoir touché ${t.render()}")
                recheck(UiActionKind.TYPE, f, ctx)
                ui.type(f, a.str("value")!!, a.bool("append") == true)
            }
            run(ctx) {
                val n = if (hasSelector(a)) ui.resolveOne(selector(a)) else ui.focusedInput() ?: throw UiException("Aucun champ sélectionné ni focalisé")
                recheck(UiActionKind.TYPE, n, ctx)
                ui.type(n, a.str("value")!!, a.bool("append") == true)
            }
        },
        def("android.ui.clear", "Vide un champ de saisie.",
            S.obj(*selectorProps), Risk.L1, SideEffect.REVERSIBLE, "Vider un champ", guardOnly) { a, ctx ->
            run(ctx) { ui.clear(ui.resolveOne(selector(a))) }
        },
        def("android.ui.paste", "Colle un texte fourni dans un champ (via le presse-papiers ; ne lit jamais le presse-papiers).",
            S.obj(*selectorProps, "value" to S.str("Texte à coller"), required = listOf("value")),
            Risk.L1, SideEffect.REVERSIBLE, "Coller du texte", classifierFor(UiActionKind.PASTE)) { a, ctx ->
            run(ctx) {
                val n = if (hasSelector(a)) ui.resolveOne(selector(a)) else ui.focusedInput() ?: throw UiException("Aucun champ focalisé")
                recheck(UiActionKind.PASTE, n, ctx)
                ui.paste(n, a.str("value")!!)
            }
        },
        def("android.ui.submit", "Valide la saisie (touche Entrée/Rechercher/Envoyer du clavier) sur un champ ou le champ focalisé.",
            S.obj(*selectorProps), Risk.L1, SideEffect.REVERSIBLE, "Valider la saisie", classifierFor(UiActionKind.SUBMIT)) { a, ctx ->
            run(ctx) {
                val n = if (hasSelector(a)) ui.resolveOne(selector(a)) else ui.focusedInput() ?: throw UiException("Aucun champ focalisé")
                recheck(UiActionKind.SUBMIT, n, ctx)
                ui.submit(n)
            }
        },
        def("android.ui.scroll", "Fait défiler (down, up, left, right, forward, backward) une liste (sélecteur optionnel).",
            S.obj(*selectorProps, "direction" to S.str("Direction", listOf("down", "up", "left", "right", "forward", "backward")), required = listOf("direction")),
            Risk.L1, SideEffect.REVERSIBLE, "Faire défiler", guardOnly) { a, ctx ->
            run(ctx) { ui.scroll(if (hasSelector(a)) ui.resolveOne(selector(a)) else null, a.str("direction")!!) }
        },
        def("android.ui.swipe", "Balayage entre deux points de l'écran.",
            S.obj("x1" to S.int("x départ"), "y1" to S.int("y départ"), "x2" to S.int("x arrivée"), "y2" to S.int("y arrivée"), "duration_ms" to S.int("Durée", 50, 3000), required = listOf("x1", "y1", "x2", "y2")),
            Risk.L1, SideEffect.REVERSIBLE, "Balayer l'écran", guardOnly) { a, ctx ->
            run(ctx) { ui.swipe(a.int("x1")!!, a.int("y1")!!, a.int("x2")!!, a.int("y2")!!, a.long("duration_ms") ?: 300) }
        },
        def("android.ui.wait_for", "Attend qu'un élément apparaisse (ou disparaisse avec gone=true).",
            S.obj(*selectorProps, "timeout_ms" to S.int("Délai max (défaut 8000)", 500, 20000), "gone" to S.bool("Attendre la disparition")),
            Risk.L0, SideEffect.NONE, "Attendre un élément", guardOnly) { a, ctx ->
            run(ctx) { ui.waitFor(selector(a), a.long("timeout_ms") ?: 8000, a.bool("gone") == true) }
        },
        def("android.nav.back", "Bouton Retour.", S.obj(), Risk.L1, SideEffect.REVERSIBLE, "Retour", guardOnly) { _, ctx ->
            run(ctx) { ui.global(AccessibilityService.GLOBAL_ACTION_BACK, "Retour") }
        },
        def("android.nav.home", "Bouton Accueil.", S.obj(), Risk.L1, SideEffect.REVERSIBLE, "Accueil", null) { _, ctx ->
            run(ctx) { ui.global(AccessibilityService.GLOBAL_ACTION_HOME, "Accueil") }
        },
        def("android.nav.recents", "Affiche les applications récentes.", S.obj(), Risk.L1, SideEffect.REVERSIBLE, "Applications récentes", null) { _, ctx ->
            run(ctx) { ui.global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Récents") }
        },
    )
}
