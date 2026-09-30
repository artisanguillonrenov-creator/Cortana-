package io.github.artisanguillonrenov.cortana.ui.workspace

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentMode
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.memory.ChatQueueEntity

/** A command of the palette (doc 02 §2.4). */
data class SlashCommand(val name: String, val label: String, val run: () -> Unit)

/** A target of "@" (doc 02 §2.5): inserted as text, and as a reference attachment when it is a file. */
data class Mention(val label: String, val kind: String, val attachment: AttachmentRef? = null)

data class ComposerState(
    val text: String,
    val files: List<AttachmentRef>,
    val queue: List<ChatQueueEntity>,
    val busy: Boolean,
    val prefs: ChatPrefs,
    val mode: ChatMode,
    val contextLevel: ContextLevel,
    val listening: Boolean,
)

data class ComposerActions(
    val setText: (String) -> Unit,
    val send: () -> Boolean,
    val stop: () -> Unit,
    val attach: (name: String, mime: String?, size: Long, open: () -> java.io.InputStream?) -> Unit,
    val setAttachmentMode: (Int, AttachmentMode) -> Unit,
    val removeAttachment: (Int) -> Unit,
    val addAttachment: (AttachmentRef) -> Unit,
    val setMode: (ChatMode) -> Unit,
    val commands: () -> List<SlashCommand>,
    val mentions: (String) -> List<Mention>,
    val queueCancel: (String) -> Unit,
    val queueConfirm: (String) -> Unit,
    val queueMove: (String, Boolean) -> Unit,
    val queueSendNow: (String) -> Unit,
    val handsFree: () -> Unit,
    val openPalette: () -> Unit,
)

/**
 * The composer (doc 02): simple at rest — [+] text [micro] [envoyer] — powerful on demand. It never
 * becomes a permanent toolbar; everything else is one tap away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(s: ComposerState, a: ComposerActions, modifier: Modifier = Modifier, focus: FocusRequester = remember { FocusRequester() }) {
    val t = LocalWorkspace.current
    val ctx = LocalContext.current
    var plusMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(10).forEach { uri -> describe(ctx, uri)?.let { (name, mime, size) -> a.attach(name, mime, size) { ctx.contentResolver.openInputStream(uri) } } }
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            // The transcription stays editable before sending (doc 02 §2.9).
            if (!spoken.isNullOrBlank()) a.setText(if (s.text.isBlank()) spoken else "${s.text.trimEnd()} $spoken")
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) a.handsFree() }

    Column(modifier.fillMaxWidth()) {
        if (s.queue.isNotEmpty()) QueueView(s.queue, a)
        Surface(color = t.composer, shape = RoundedCornerShape(t.radiusComposer), modifier = Modifier.fillMaxWidth().border(1.dp, t.border.copy(alpha = if (t.highContrast) 1f else 0.5f), RoundedCornerShape(t.radiusComposer))) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                if (s.files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.files.forEachIndexed { i, f -> DraftAttachment(f, onMode = { m -> a.setAttachmentMode(i, m) }, onRemove = { a.removeAttachment(i) }) }
                }
                SlashAndMentions(s.text, a)
                Row(verticalAlignment = Alignment.Bottom) {
                    Box {
                        IconButton(onClick = { plusMenu = true }) { Icon(Icons.Default.Add, "Joindre, mode, commandes") }
                        DropdownMenu(plusMenu, { plusMenu = false }) {
                            DropdownMenuItem(text = { Text("Joindre des fichiers ou images") }, onClick = { plusMenu = false; runCatching { picker.launch(arrayOf("*/*")) } })
                            DropdownMenuItem(text = { Text("Coller une image ou un fichier copié") }, onClick = {
                                plusMenu = false
                                // Doc 02 §2.2 "collage": an image or file copied in another app arrives as a content:// URI.
                                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                val uri = runCatching { cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri }.getOrNull()?.takeIf { it.scheme == "content" }
                                if (uri != null) describe(ctx, uri)?.let { (name, mime, size) -> a.attach(name, mime, size) { ctx.contentResolver.openInputStream(uri) } }
                                else runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                                    ?.let { pasted -> a.setText(if (s.text.isBlank()) pasted else s.text.trimEnd() + " " + pasted) }
                            })
                            DropdownMenuItem(text = { Text("Commandes…  (/)") }, onClick = { plusMenu = false; a.openPalette() })
                            HorizontalDivider()
                            ChatMode.entries.filter { it != ChatMode.COMPARE }.forEach { m ->
                                DropdownMenuItem(text = { Text((if (m == s.mode) "✓ " else "   ") + "Mode " + m.label) }, onClick = { plusMenu = false; a.setMode(m) })
                            }
                        }
                    }
                    TextField(
                        value = s.text, onValueChange = a.setText,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 240.dp).focusRequester(focus).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val ctrl = e.isCtrlPressed || e.isMetaPressed
                            when {
                                e.key == Key.Enter && ctrl -> { a.send(); true }
                                e.key == Key.Enter && !e.isShiftPressed && s.prefs.enterToSend -> { a.send(); true }
                                e.key == Key.Escape && s.busy -> { a.stop(); true }
                                e.key == Key.K && ctrl -> { a.openPalette(); true }
                                else -> false
                            }
                        },
                        placeholder = { Text(if (s.mode == ChatMode.CHAT) "Écrivez à Cortana…" else "Écrivez à Cortana… (mode ${s.mode.label.lowercase()})") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    MicButton(
                        listening = s.listening,
                        onTap = {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictez votre message")
                            runCatching { dictation.launch(i) }
                        },
                        onLong = {
                            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) a.handsFree()
                            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    )
                    val canSend = s.text.isNotBlank() || s.files.any { it.status == "ready" }
                    if (s.busy && !canSend) {
                        IconButton(onClick = a.stop, modifier = Modifier.semantics { contentDescription = "Arrêter la réponse (Échap)" }) { Text("⏹", style = MaterialTheme.typography.titleLarge) }
                    } else {
                        IconButton(onClick = { a.send() }, enabled = canSend) {
                            Icon(Icons.AutoMirrored.Filled.Send, if (s.busy) "Mettre en file (Cortana répond)" else "Envoyer")
                        }
                    }
                }
                if (s.prefs.contextMeter && !s.contextLevel.ok) {
                    Text(
                        (if (s.contextLevel == ContextLevel.COMPACT) "⚠ " else "ℹ ") + s.contextLevel.label +
                            if (s.contextLevel == ContextLevel.COMPACT) " : les échanges anciens seront bientôt résumés." else "",
                        style = MaterialTheme.typography.labelSmall, color = t.muted, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/** Name, type and size of a picked document (never its path). */
internal fun describe(ctx: android.content.Context, uri: Uri): Triple<String, String?, Long>? = runCatching {
    var name = uri.lastPathSegment ?: "fichier"
    var size = -1L
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
        }
    }
    Triple(name, ctx.contentResolver.getType(uri), size)
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicButton(listening: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    val t = LocalWorkspace.current
    Box(
        Modifier.size(48.dp).combinedClickable(onClick = onTap, onLongClick = onLong, onClickLabel = "Dicter", onLongClickLabel = "Conversation vocale mains libres")
            .semantics { role = Role.Button; contentDescription = if (listening) "Écoute en cours" else "Dicter (appui long : mains libres)" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = if (listening) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
            Text(if (listening) "🎙" else "🎤", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(6.dp), color = if (listening) MaterialTheme.colorScheme.onPrimaryContainer else t.muted)
        }
    }
}

@Composable
internal fun DraftAttachment(f: AttachmentRef, onMode: (AttachmentMode) -> Unit, onRemove: () -> Unit) {
    val t = LocalWorkspace.current
    var menu by remember { mutableStateOf(false) }
    val ok = f.status == "ready"
    Surface(color = if (ok) t.elevated else t.critical, contentColor = if (ok) MaterialTheme.colorScheme.onSurface else t.onCritical, shape = RoundedCornerShape(t.radiusSmall)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Column(Modifier.widthIn(max = 220.dp).clickable(enabled = ok, onClickLabel = "Choisir comment Cortana utilise ce fichier") { menu = true }.padding(vertical = 6.dp)) {
                Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val how = when (f.mode) { AttachmentMode.READ -> "lire"; AttachmentMode.ANALYZE -> "analyser"; AttachmentMode.REFERENCE -> "référence" }
                Text(if (ok) "${sizeLabel(f.sizeBytes)} · $how ▾" else (f.note ?: f.status), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Lire : le contenu est donné à Cortana") }, onClick = { menu = false; onMode(AttachmentMode.READ) })
                    DropdownMenuItem(text = { Text("Analyser : Cortana utilise ses outils") }, onClick = { menu = false; onMode(AttachmentMode.ANALYZE) })
                    DropdownMenuItem(text = { Text("Référence : seulement nommé") }, onClick = { menu = false; onMode(AttachmentMode.REFERENCE) })
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Retirer ${f.name}") }
        }
    }
}

/** Messages waiting for Cortana (doc 02 §2.6): reorder, remove, confirm an old one, or interrupt and send now. */
@Composable
private fun QueueView(queue: List<ChatQueueEntity>, a: ComposerActions) {
    val t = LocalWorkspace.current
    Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusCard), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text("En file (${queue.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
            queue.forEachIndexed { i, q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (q.status == "confirm") "⏸ " else "${i + 1}. ") + q.text.lineSequence().first().take(120), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    if (q.status == "confirm") TextButton(onClick = { a.queueConfirm(q.id) }) { Text("Envoyer") }
                    else {
                        if (i > 0) TextButton(onClick = { a.queueMove(q.id, true) }) { Text("↑") }
                        if (i < queue.lastIndex) TextButton(onClick = { a.queueMove(q.id, false) }) { Text("↓") }
                        TextButton(onClick = { a.queueSendNow(q.id) }) { Text("Maintenant") }
                    }
                    IconButton(onClick = { a.queueCancel(q.id) }) { Icon(Icons.Default.Close, "Retirer de la file") }
                }
                if (q.status == "confirm") Text("Écrit il y a longtemps ou avant un redémarrage : confirmez avant l'envoi.", style = MaterialTheme.typography.labelSmall,
                    color = t.muted, modifier = Modifier.padding(start = 28.dp))
            }
        }
    }
}

private val MENTION = Regex("(?:^|\\s)@([\\p{L}\\p{N}_.-]{0,40})$")

/** "/" opens the command palette, "@" the mentions (doc 02 §2.4–2.5); both are searchable as you type. */
@Composable
internal fun SlashAndMentions(text: String, a: ComposerActions) {
    val t = LocalWorkspace.current
    val slash = text.startsWith("/") && !text.contains(' ') && !text.contains('\n')
    val at = MENTION.find(text)?.groupValues?.get(1)
    val options: List<Pair<String, () -> Unit>> = when {
        slash -> a.commands().filter { it.name.startsWith(text.drop(1), ignoreCase = true) }.map { c -> "/${c.name} — ${c.label}" to { a.setText(""); c.run() } }
        at != null -> a.mentions(at).take(8).map { m ->
            "@${m.label} (${m.kind})" to {
                a.setText(text.dropLast(at.length + 1) + "@" + m.label + " ")
                m.attachment?.let(a.addAttachment)
            }
        }
        else -> emptyList()
    }
    if (options.isEmpty()) return
    Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Column {
            options.take(10).forEach { (label, run) ->
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = run).padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
fun FocusOnStart(focus: FocusRequester, key: Any?) {
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }
}
