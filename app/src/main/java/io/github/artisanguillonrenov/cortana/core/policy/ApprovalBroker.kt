package io.github.artisanguillonrenov.cortana.core.policy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.artisanguillonrenov.cortana.R
import io.github.artisanguillonrenov.cortana.service.Notifications
import io.github.artisanguillonrenov.cortana.ui.approval.ApprovalActivity
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class ApprovalRequest(
    val id: String = Ids.new(),
    val capability: String,
    /** French description of the action. */
    val action: String,
    val target: String?,
    val params: String,
    val risk: Risk,
    val reversible: Boolean,
    val tainted: Boolean,
    val taintSources: List<String>,
    val reasons: List<String>,
    val biometric: Boolean,
    /** Offer "always allow this capability" (never for L3 or tainted egress). */
    val allowGrant: Boolean,
    /** Offer "always allow this destination". */
    val destination: String?,
    /** Binds the approval to this exact action (hash of capability + canonical args). */
    val bindingHash: String,
)

/** Reason prefix of a pause that refuses the pending action (recorded as "refused", not "cancelled"). */
const val OWNER_REFUSAL = "Action refusée par le propriétaire"

data class ApprovalDecision(val approved: Boolean, val rememberGrant: Boolean = false, val rememberDestination: Boolean = false, val reason: String? = null)

/**
 * §9.6 — bridges the orchestrator (suspends) and the approval screen (FLAG_SECURE Activity).
 * One pending request at a time; any timeout, kill switch or dismissal is a refusal.
 */
class ApprovalBroker(private val context: Context) {
    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending
    private var deferred: CompletableDeferred<ApprovalDecision>? = null
    private val mutex = Mutex()

    suspend fun request(req: ApprovalRequest, timeoutMs: Long = 180_000): ApprovalDecision = mutex.withLock {
        val d = CompletableDeferred<ApprovalDecision>()
        deferred = d
        _pending.value = req
        launchUi(req)
        try {
            withTimeoutOrNull(timeoutMs) { d.await() } ?: ApprovalDecision(false, reason = "délai dépassé")
        } finally {
            _pending.value = null
            deferred = null
            NotificationManagerCompat.from(context).cancel(Notifications.ID_APPROVAL)
        }
    }

    fun resolve(requestId: String, decision: ApprovalDecision) {
        val cur = _pending.value ?: return
        if (cur.id != requestId) return // approval bound to that exact request
        deferred?.complete(decision)
    }

    /**
     * Opens the secure approval screen again for the pending request (Workspace "Examiner et autoriser").
     * Only that screen can approve; a chat component can only reopen it or refuse.
     */
    fun reopen(requestId: String) {
        val cur = _pending.value ?: return
        if (cur.id == requestId) launchUi(cur)
    }

    fun cancelPending(reason: String) {
        deferred?.complete(ApprovalDecision(false, reason = reason))
    }

    private fun launchUi(req: ApprovalRequest) {
        val intent = Intent(context, ApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra(ApprovalActivity.EXTRA_ID, req.id)
        // Starting from the accessibility service / visible UI is allowed; the notification is the fallback.
        runCatching { context.startActivity(intent) }.onFailure { CLog.w("approval activity launch failed", it) }
        val pi = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, Notifications.CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_cortana)
            .setContentTitle(if (req.biometric) "Autorisation par empreinte requise" else "Confirmation requise")
            .setContentText(req.action)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        if (Notifications(context).canPost()) {
            @android.annotation.SuppressLint("MissingPermission")
            val posted = runCatching { NotificationManagerCompat.from(context).notify(Notifications.ID_APPROVAL, n) }
            posted.onFailure { CLog.w("approval notification failed", it) }
        }
    }
}
