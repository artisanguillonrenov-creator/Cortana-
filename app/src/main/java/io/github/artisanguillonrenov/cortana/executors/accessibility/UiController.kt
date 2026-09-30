package io.github.artisanguillonrenov.cortana.executors.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.artisanguillonrenov.cortana.service.CortanaAccessibilityService
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** In-process link between the system-bound AccessibilityService and the rest of the app (§1). */
object AccessibilityBridge {
    val service = MutableStateFlow<CortanaAccessibilityService?>(null)
    @Volatile var lastEventAt: Long = 0L
    @Volatile var lastActionAt: Long = 0L
    @Volatile var automationActive: Boolean = false
    @Volatile var foregroundPackage: String? = null
    @Volatile var foregroundClass: String? = null
    /** Set when the owner touches the screen during automation (§10 user takeover). */
    val takeover = MutableStateFlow(false)
    private const val TAKEOVER_WINDOW_MS = 2000L

    fun onEvent(e: AccessibilityEvent, ownPackage: String) {
        val now = System.currentTimeMillis()
        lastEventAt = now
        val pkg = e.packageName?.toString()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && pkg != ownPackage) {
            foregroundPackage = pkg
            foregroundClass = e.className?.toString()
        }
        if (automationActive && pkg != ownPackage && now - lastActionAt > TAKEOVER_WINDOW_MS) {
            when (e.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> takeover.value = true
            }
        }
    }
}

data class UiNode(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val className: String,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val password: Boolean,
    val selected: Boolean,
    val range: String?,
    val packageName: String?,
) {
    fun describe(): String = buildString {
        append(className.substringAfterLast('.'))
        if (password) append(" [mot de passe]") else text?.let { append(" \"").append(it).append('"') }
        desc?.let { append(" desc=\"").append(it).append('"') }
        resId?.let { append(" id=").append(it.substringAfterLast('/')) }
    }

    fun line(): String = buildString {
        append('[').append(index).append("] ").append(describe())
        append(" (").append(bounds.left).append(',').append(bounds.top).append(',').append(bounds.right).append(',').append(bounds.bottom).append(')')
        val flags = listOfNotNull(
            "clic".takeIf { clickable }, "appui-long".takeIf { longClickable }, "saisie".takeIf { editable },
            "défilable".takeIf { scrollable }, (if (checked) "coché" else "non-coché").takeIf { checkable },
            "désactivé".takeIf { !enabled }, "focus".takeIf { focused }, "sélectionné".takeIf { selected },
        )
        if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
        range?.let { append(" valeur=").append(it) }
    }
}

data class ScreenSnapshot(
    val packageName: String?,
    val activity: String?,
    val nodes: List<UiNode>,
    val hash: String,
    val width: Int,
    val height: Int,
    val truncated: Boolean,
) {
    val screenText: String get() = nodes.mapNotNull { if (it.password) null else it.text ?: it.desc }.joinToString(" ").take(3000)

    fun render(): String = buildString {
        appendLine("Application : ${packageName ?: "?"}" + (activity?.let { " (activité : ${it.substringAfterLast('.')})" } ?: ""))
        appendLine("Écran ${width}x$height — ${nodes.size} éléments${if (truncated) " (liste tronquée)" else ""} — empreinte ${hash.take(8)}")
        nodes.forEach { appendLine(it.line()) }
    }
}

/** A target description coming from the model (§8.3 selector). */
data class Selector(
    val node: Int? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    val textRegex: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val index: Int? = null,
    val clickable: Boolean? = null,
) {
    fun isEmpty() = node == null && resourceId == null && text == null && textContains == null && textRegex == null &&
        contentDescription == null && className == null

    fun matches(n: UiNode): Boolean {
        if (resourceId != null) {
            val r = n.resId ?: return false
            if (r != resourceId && r.substringAfterLast('/') != resourceId.substringAfterLast('/')) return false
        }
        if (text != null && !(n.text.equals(text, true) || n.desc.equals(text, true))) return false
        if (textContains != null && !((n.text?.contains(textContains, true) == true) || (n.desc?.contains(textContains, true) == true))) return false
        val rx = textRegex
        if (rx != null) {
            val re = runCatching { Regex(rx, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
            if (!(n.text?.let(re::containsMatchIn) == true || n.desc?.let(re::containsMatchIn) == true)) return false
        }
        if (contentDescription != null && !(n.desc?.contains(contentDescription, true) == true)) return false
        if (className != null && !n.className.endsWith(className, true)) return false
        if (packageName != null && n.packageName != packageName) return false
        if (clickable != null && n.clickable != clickable) return false
        return true
    }
}

class UiException(message: String) : Exception(message)

/**
 * §8.3 — observe/act through the AccessibilityService. Every action is followed by a settle wait;
 * the executor refuses to act while the kill switch is halted or the device is locked.
 */
class UiController(private val context: Context, private val isHalted: () -> Boolean) {
    @Volatile var lastSnapshot: ScreenSnapshot? = null
        private set

    private fun svc(): CortanaAccessibilityService =
        AccessibilityBridge.service.value ?: throw UiException(
            "Service d'accessibilité Cortana inactif. Activez-le : Paramètres → Accessibilité → Applications installées → Cortana (si c'est grisé : Infos de l'appli → ⋮ → Autoriser les paramètres restreints)."
        )

    fun isServiceRunning(): Boolean = AccessibilityBridge.service.value != null

    private fun guard() {
        if (isHalted()) throw UiException("Automatisation arrêtée (STOP).")
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) throw UiException("L'appareil est verrouillé : Cortana n'agit jamais sur l'écran de verrouillage. Déverrouillez la tablette puis relancez.")
    }

    suspend fun settle(maxMs: Long = 2500, quietMs: Long = 350) {
        delay(120)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (System.currentTimeMillis() - AccessibilityBridge.lastEventAt >= quietMs) return
            delay(80)
        }
    }

    private fun roots(s: AccessibilityService): List<AccessibilityNodeInfo> {
        s.rootInActiveWindow?.let { return listOf(it) }
        return s.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
    }

    fun observe(maxNodes: Int = 250): ScreenSnapshot {
        val s = svc()
        val rootList = roots(s)
        val out = mutableListOf<UiNode>()
        var truncated = false
        val dm = context.resources.displayMetrics
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= maxNodes) { truncated = true; return }
            if (depth > 60) return
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.replace('\n', ' ')?.take(160)?.takeIf { it.isNotBlank() }
            val desc = n.contentDescription?.toString()?.replace('\n', ' ')?.take(160)?.takeIf { it.isNotBlank() }
            val rid = n.viewIdResourceName
            val interesting = text != null || desc != null || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || n.isLongClickable
            if (interesting) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val ri = n.rangeInfo
                    out += UiNode(
                        index = out.size, node = n, className = n.className?.toString() ?: "View",
                        text = if (n.isPassword) null else text, desc = desc, resId = rid, bounds = r,
                        clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable,
                        scrollable = n.isScrollable, checkable = n.isCheckable, checked = n.isChecked, enabled = n.isEnabled,
                        focused = n.isFocused, password = n.isPassword, selected = n.isSelected,
                        range = ri?.let { "${it.current.toInt()}/${it.min.toInt()}..${it.max.toInt()}" },
                        packageName = n.packageName?.toString(),
                    )
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                visit(c, depth + 1)
            }
        }
        rootList.forEach { visit(it, 0) }
        val pkg = rootList.firstOrNull()?.packageName?.toString() ?: AccessibilityBridge.foregroundPackage
        val hash = Hash.sha256(pkg + out.joinToString("|") { "${it.className}:${it.text}:${it.desc}:${it.resId}:${it.bounds.flattenToString()}" })
        val snap = ScreenSnapshot(pkg, AccessibilityBridge.foregroundClass, out, hash, dm.widthPixels, dm.heightPixels, truncated)
        lastSnapshot = snap
        return snap
    }

    fun find(sel: Selector): List<UiNode> {
        if (sel.node != null) {
            val snap = lastSnapshot ?: throw UiException("Aucune observation récente : appelle android_ui_observe d'abord.")
            val n = snap.nodes.getOrNull(sel.node) ?: throw UiException("Élément [${sel.node}] inexistant dans la dernière observation.")
            if (!n.node.refresh()) throw UiException("L'élément [${sel.node}] a disparu : observe à nouveau l'écran.")
            return listOf(n)
        }
        if (sel.isEmpty()) throw UiException("Sélecteur vide : indique node, resource_id, text, text_contains, content_description…")
        val matches = observe(400).nodes.filter(sel::matches)
        return if (sel.index != null) listOfNotNull(matches.getOrNull(sel.index)) else matches
    }

    fun resolveOne(sel: Selector): UiNode {
        val m = find(sel)
        if (m.isEmpty()) throw UiException("Aucun élément ne correspond au sélecteur. Observe l'écran et réessaie.")
        // Prefer actionable matches when ambiguous.
        return m.firstOrNull { it.clickable || it.editable } ?: m.first()
    }

    fun nodeAt(x: Int, y: Int): UiNode? {
        val snap = observe(400)
        return snap.nodes.filter { it.bounds.contains(x, y) }.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
    }

    private fun markAction() {
        AccessibilityBridge.lastActionAt = System.currentTimeMillis()
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (if (long) cur.isLongClickable else cur.isClickable) return cur
            cur = cur.parent; depth++
        }
        return null
    }

    suspend fun click(target: UiNode, long: Boolean = false): String {
        guard()
        markAction()
        val actionable = clickableAncestor(target.node, long)
        val ok = actionable?.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true
        val result = if (ok) {
            "${if (long) "Appui long" else "Clic"} sur ${target.describe()}"
        } else {
            val cx = target.bounds.centerX(); val cy = target.bounds.centerY()
            if (!tap(cx, cy, if (long) 700 else 60)) throw UiException("Action refusée par le système sur ${target.describe()}")
            "${if (long) "Appui long" else "Clic"} (repli par coordonnées $cx,$cy) sur ${target.describe()}"
        }
        markAction()
        settle()
        return result
    }

    suspend fun tap(x: Int, y: Int, durationMs: Long = 60): Boolean {
        guard()
        markAction()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build())
    }

    suspend fun clickPoint(x: Int, y: Int, long: Boolean): String {
        if (!tap(x, y, if (long) 700 else 60)) throw UiException("Geste refusé")
        markAction()
        settle()
        return "Toucher aux coordonnées ($x,$y) — repli par coordonnées"
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        guard()
        markAction()
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val ok = gesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 3000))).build())
        markAction()
        settle()
        return if (ok) "Balayage ($x1,$y1) → ($x2,$y2)" else throw UiException("Balayage refusé")
    }

    private suspend fun gesture(g: GestureDescription): Boolean {
        val s = svc()
        return suspendCancellableCoroutine { cont ->
            val dispatched = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    suspend fun type(target: UiNode, text: String, append: Boolean): String {
        guard()
        if (target.password) throw UiException("Saisie dans un champ de mot de passe refusée sans autorisation L3 spécifique.")
        val n = target.node
        if (!n.isEditable) throw UiException("${target.describe()} n'est pas un champ de saisie.")
        markAction()
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val current = if (append && !n.isShowingHintText) n.text?.toString().orEmpty() else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw UiException("Saisie refusée par ${target.describe()}")
        markAction()
        settle()
        return "Texte saisi dans ${target.describe()} (${text.length} caractères)"
    }

    suspend fun clear(target: UiNode): String {
        guard()
        markAction()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        if (!target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw UiException("Effacement refusé")
        settle()
        return "Champ vidé : ${target.describe()}"
    }

    /** Sets the clip and pastes. Never reads the owner's clipboard (§10). */
    suspend fun paste(target: UiNode, text: String): String {
        guard()
        if (target.password) throw UiException("Collage dans un champ de mot de passe refusé.")
        markAction()
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Cortana", text))
        if (!target.node.isFocused) target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!target.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) throw UiException("Collage refusé par ${target.describe()}")
        settle()
        return "Texte collé dans ${target.describe()}"
    }

    fun focusedInput(): UiNode? {
        val s = svc()
        val f = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val r = Rect(); f.getBoundsInScreen(r)
        return UiNode(-1, f, f.className?.toString() ?: "EditText", f.text?.toString(), f.contentDescription?.toString(), f.viewIdResourceName, r,
            f.isClickable, f.isLongClickable, f.isEditable, f.isScrollable, f.isCheckable, f.isChecked, f.isEnabled, f.isFocused, f.isPassword, f.isSelected, null, f.packageName?.toString())
    }

    suspend fun submit(target: UiNode): String {
        guard()
        markAction()
        val ok = target.node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        if (!ok) throw UiException("Validation (touche Entrée) refusée par ${target.describe()}")
        settle()
        return "Validation envoyée depuis ${target.describe()}"
    }

    suspend fun scroll(target: UiNode?, direction: String): String {
        guard()
        markAction()
        val snap = lastSnapshot ?: observe()
        val scrollable = target?.let { t ->
            var cur: AccessibilityNodeInfo? = t.node; var d = 0
            while (cur != null && !cur.isScrollable && d < 8) { cur = cur.parent; d++ }
            cur
        } ?: snap.nodes.filter { it.scrollable }.maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.node
        val action = when (direction) {
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            "backward" -> AccessibilityAction.ACTION_SCROLL_BACKWARD
            else -> AccessibilityAction.ACTION_SCROLL_FORWARD
        }
        var ok = false
        if (scrollable != null) {
            ok = scrollable.actionList.any { it.id == action.id } && scrollable.performAction(action.id)
            if (!ok) {
                val fallback = if (direction in setOf("up", "left", "backward")) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ok = scrollable.performAction(fallback)
            }
        }
        if (!ok) {
            // Gesture fallback across the middle of the screen.
            val w = snap.width; val h = snap.height
            val (x1, y1, x2, y2) = when (direction) {
                "up", "backward" -> listOf(w / 2, h * 3 / 10, w / 2, h * 7 / 10)
                "left" -> listOf(w * 3 / 10, h / 2, w * 7 / 10, h / 2)
                "right" -> listOf(w * 7 / 10, h / 2, w * 3 / 10, h / 2)
                else -> listOf(w / 2, h * 7 / 10, w / 2, h * 3 / 10)
            }
            return swipe(x1, y1, x2, y2, 350) + " (repli par geste)"
        }
        markAction()
        settle()
        return "Défilement $direction effectué"
    }

    suspend fun global(action: Int, label: String): String {
        guard()
        markAction()
        if (!svc().performGlobalAction(action)) throw UiException("Action globale « $label » refusée")
        markAction()
        settle()
        return "Action « $label » effectuée"
    }

    suspend fun waitFor(sel: Selector, timeoutMs: Long, gone: Boolean): String {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isHalted()) throw UiException("Automatisation arrêtée (STOP).")
            val found = runCatching { observe(400).nodes.any(sel::matches) }.getOrDefault(false)
            if (found != gone) return if (gone) "L'élément a disparu" else "Élément présent"
            delay(400)
        }
        throw UiException("Délai dépassé (${timeoutMs / 1000} s) en attendant l'élément")
    }
}
