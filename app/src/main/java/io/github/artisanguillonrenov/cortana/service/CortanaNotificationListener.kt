package io.github.artisanguillonrenov.cortana.service

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.core.comms.NotificationItem
import io.github.artisanguillonrenov.cortana.util.CLog

/**
 * Notification access (doc 05 §8), granted by the owner in Android settings. Everything is handed
 * to the NotificationHub, which keeps only the apps the owner allowed in Cortana. Cortana's own
 * notifications are ignored (no loops).
 */
class CortanaNotificationListener : NotificationListenerService() {
    private val hub get() = (application as CortanaApp).container.notificationHub

    override fun onListenerConnected() {
        hub.connected = true
        runCatching { activeNotifications?.forEach(::relay) }
    }

    override fun onListenerDisconnected() { hub.connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) = relay(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = hub.removed(sbn.key)

    private fun relay(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        try {
            val n = sbn.notification
            val e = n.extras
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
            val replier: (suspend (String) -> Unit)? = reply?.let { a ->
                { text: String ->
                    val inputs = a.remoteInputs
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
                    a.actionIntent.send(this, 0, intent)
                }
            }
            hub.posted(NotificationItem(
                key = sbn.key, packageName = sbn.packageName, appLabel = label,
                title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(2000),
                postedAt = sbn.postTime, canReply = replier != null,
                conversation = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            ), replier)
        } catch (t: Throwable) { CLog.w("notification relay failed", t) }
    }
}
