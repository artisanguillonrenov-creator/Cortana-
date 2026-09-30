package io.github.artisanguillonrenov.cortana.ui.workspace

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Content another app gave Cortana (share sheet, "texte sélectionné"): it becomes a draft, never a sent message. */
data class SharedContent(val text: String?, val uris: List<Uri>, val subject: String? = null)

/** Holds the last share until the Workspace takes it (doc 02 §2.2: partage Android, texte sélectionné). */
object ShareInbox {
    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending

    fun offer(c: SharedContent) { _pending.value = c }
    fun take(): SharedContent? = _pending.getAndUpdate { null }

    /** ACTION_SEND, ACTION_SEND_MULTIPLE and ACTION_PROCESS_TEXT; anything else is not a share. */
    fun parse(intent: Intent?): SharedContent? {
        intent ?: return null
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> return null
        }?.take(100_000)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }.filter { it.scheme == "content" }.take(10) // never a file:// path handed over by another app
        if (text.isNullOrBlank() && uris.isEmpty()) return null
        return SharedContent(text, uris, intent.getStringExtra(Intent.EXTRA_SUBJECT)?.take(200))
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
