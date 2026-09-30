package io.github.artisanguillonrenov.cortana.core.comms

import io.github.artisanguillonrenov.cortana.core.vision.SensitiveText
import io.github.artisanguillonrenov.cortana.core.vision.TextMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** "When a notification from [packageName] containing [contains] arrives, do [objective]." */
@Serializable
data class NotificationTriggerSpec(
    val id: String,
    val packageName: String? = null,
    val contains: String? = null,
    val objective: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

/**
 * The one owner of notification access (doc 05 §8). Only notifications of apps the owner allowed
 * are kept (in memory, the latest 200); others are dropped on arrival (only the app name is noted
 * so it can be offered in Settings). Direct replies go through the notification's own reply action.
 * Matching triggers start tasks through [fire] — at most once a minute per trigger.
 */
class NotificationHub(
    private val allowedApps: () -> Set<String>,
    private val triggers: () -> List<NotificationTriggerSpec>,
    private val fire: suspend (NotificationTriggerSpec, NotificationItem) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var connected = false
    private val items = LinkedHashMap<String, NotificationItem>()
    private val repliers = ConcurrentHashMap<String, suspend (String) -> Unit>()
    private val lastFired = ConcurrentHashMap<String, Long>()
    val seenPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun posted(item: NotificationItem, replier: (suspend (String) -> Unit)?) {
        seenPackages += item.packageName
        if (item.packageName !in allowedApps()) return
        synchronized(items) {
            items.remove(item.key); items[item.key] = item
            while (items.size > 200) items.remove(items.keys.first())
        }
        if (replier != null) repliers[item.key] = replier else repliers.remove(item.key)
        for (t in triggers()) {
            if (!t.enabled || !matches(t, item)) continue
            val now = clock()
            val prev = lastFired[t.id]
            if (prev != null && now - prev < 60_000) continue
            lastFired[t.id] = now
            scope.launch { fire(t, item) }
        }
    }

    fun removed(key: String) { synchronized(items) { items.remove(key) }; repliers.remove(key) }

    fun list(packageName: String? = null, query: String? = null, limit: Int = 30): List<NotificationItem> {
        val allowed = allowedApps()
        val all = synchronized(items) { items.values.toList() }
        return all.asReversed().asSequence()
            .filter { it.packageName in allowed && (packageName == null || it.packageName == packageName || it.appLabel.equals(packageName, true)) }
            .filter { query.isNullOrBlank() || TextMatch.normalize("${it.title} ${it.text}").contains(TextMatch.normalize(query)) }
            .take(limit).toList()
    }

    fun get(key: String) = synchronized(items) { items[key] }?.takeIf { it.packageName in allowedApps() }

    suspend fun reply(key: String, text: String) {
        val r = repliers[key] ?: throw CommsException("Cette notification ne propose pas de réponse directe (ou n'est plus affichée).")
        r(text)
    }

    companion object {
        fun matches(t: NotificationTriggerSpec, n: NotificationItem): Boolean =
            (t.packageName == null || t.packageName == n.packageName) &&
                (t.contains.isNullOrBlank() || TextMatch.normalize("${n.title.orEmpty()} ${n.text.orEmpty()}").contains(TextMatch.normalize(t.contains)))

        /** One-time codes, card numbers and IBANs in notification text are never shown to a model. */
        fun masked(text: String?): String? = text?.let { SensitiveText.mask(it) }
    }
}
