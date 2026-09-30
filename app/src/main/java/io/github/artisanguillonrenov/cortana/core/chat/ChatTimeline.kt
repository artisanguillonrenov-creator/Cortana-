package io.github.artisanguillonrenov.cortana.core.chat

import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.MessageNode
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.model.ToolCall
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Alternatives at a point of the tree (doc 03 §3.6–3.7): "Réponse 2/3", "Version 1/2". */
data class Variants(val index: Int, val ids: List<String>) {
    val total get() = ids.size
    val label get() = "${index + 1}/${ids.size}"
}

sealed interface TimelineItem {
    val key: String

    data class User(val message: MessageEntity, val parts: List<MessagePart>, val versions: Variants?, val meta: MessageMeta) : TimelineItem {
        override val key get() = message.id
    }

    /**
     * Cortana's answer to one user turn: its visible text, tool activity and sources, merged across the
     * rows of the turn (tool rounds, continuations). [first] is the row where the turn starts (variants).
     */
    data class Assistant(
        val first: MessageEntity,
        val last: MessageEntity,
        val rows: List<MessageEntity>,
        val parts: List<MessagePart>,
        val text: String,
        val sources: List<Source>,
        val status: String,
        val meta: MessageMeta,
        val variants: Variants?,
        val parentUserId: String?,
    ) : TimelineItem {
        override val key get() = first.id
        val canContinue get() = status == MessageStatus.STOPPED || status == MessageStatus.INTERRUPTED || meta.finishReason == "length"
    }

    /** 2 to 4 answers to the same message from different models (doc 08 §8.2); [selected] is the one the conversation follows. */
    data class Compare(val userId: String, val group: String, val lanes: List<MessageEntity>, val selected: String) : TimelineItem {
        override val key get() = "cmp-$group"
    }

    data class System(val message: MessageEntity, val part: MessagePart) : TimelineItem {
        override val key get() = message.id
    }
}

/**
 * Builds what the owner sees from the active branch (pure, tested on the JVM). Hidden rows (repair
 * prompts, step results, continuation instructions) never appear; provider reasoning is never read.
 */
object ChatTimeline {
    fun meta(m: MessageEntity): MessageMeta = m.metaJson?.let { runCatching { AppJson.decodeFromString(MessageMeta.serializer(), it) }.getOrNull() } ?: MessageMeta()

    /**
     * [path] oldest first; [children] maps a parent id ("" for roots) to its children (all branches) for
     * variant navigation; [live] overrides the text of streaming rows with the freshest stream text.
     */
    fun build(
        path: List<MessageEntity>,
        children: Map<String, List<MessageNode>> = emptyMap(),
        live: Map<String, String> = emptyMap(),
        label: (String) -> String = { it },
        markdown: Boolean = true,
        /** Markdown parser (screens pass a memoised one: only changed texts are parsed again while streaming). */
        parse: (String) -> List<MdBlock> = Markdown::parse,
    ): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        var turn = mutableListOf<MessageEntity>()
        var lastUser: MessageEntity? = null
        // The direct child of the last user message on this path: where regenerations branch off.
        var turnRoot: MessageEntity? = null
        var variantsShown = false
        val calls = HashMap<String, ToolCall>()
        fun closeTurn() {
            if (turn.isEmpty()) return
            assistant(turn, lastUser, turnRoot.takeIf { !variantsShown }, children, live, calls, label, markdown, parse)?.let { out += it; variantsShown = true }
            turn = mutableListOf()
        }
        for (m in path) {
            if (lastUser != null && turnRoot == null && m.parentId == lastUser?.id) turnRoot = m
            m.toolCallsJson?.takeIf { m.role == Roles.ASSISTANT }?.let { j ->
                runCatching { AppJson.decodeFromString(ListSerializer(ToolCall.serializer()), j) }.getOrNull()?.forEach { calls[it.id] = it }
            }
            when {
                m.role == Roles.USER && !m.hidden -> {
                    closeTurn()
                    val meta = meta(m)
                    val siblings = children[m.parentId ?: ""].orEmpty().filter { it.role == Roles.USER && !it.hidden }
                    out += TimelineItem.User(m, userParts(m, meta, markdown, parse), variants(m.id, siblings.map { it.id }), meta)
                    lastUser = m; turnRoot = null; variantsShown = false
                }
                m.role == Roles.SYSTEM -> {
                    closeTurn()
                    out += TimelineItem.System(m, systemPart(m))
                }
                else -> turn += m
            }
        }
        closeTurn()
        return groupCompare(out, children)
    }

    private fun variants(current: String, ids: List<String>): Variants? = if (ids.size > 1 && current in ids) Variants(ids.indexOf(current), ids) else null

    private fun userParts(m: MessageEntity, meta: MessageMeta, markdown: Boolean, parse: (String) -> List<MdBlock>): List<MessagePart> =
        listOf(if (markdown) MessagePart.Markdown(parse(m.text)) else MessagePart.Plain(m.text)) +
            meta.attachments.map { a -> if (a.mime.startsWith("image/")) MessagePart.Image(a) else MessagePart.File(a) }

    fun systemPart(m: MessageEntity): MessagePart {
        m.toolCallsJson?.takeIf { it.contains("\"council\"") }?.let { j ->
            val o = runCatching { AppJson.parseToJsonElement(j) as JsonObject }.getOrNull()
            val run = (o?.get("council") as? JsonPrimitive)?.contentOrNull
            if (o != null && run != null) return MessagePart.Council(run, o["summary"].toString())
        }
        val meta = meta(m)
        return MessagePart.SystemEvent(meta.event ?: "notice", m.text)
    }

    private fun assistant(
        rows: List<MessageEntity>, user: MessageEntity?, root: MessageEntity?, children: Map<String, List<MessageNode>>, live: Map<String, String>,
        calls: Map<String, ToolCall>, label: (String) -> String, markdown: Boolean, parse: (String) -> List<MdBlock>,
    ): TimelineItem.Assistant? {
        val visible = rows.filter { !it.hidden || it.role == Roles.TOOL }
        val first = rows.first()
        val parts = mutableListOf<MessagePart>()
        val sources = mutableListOf<Source>()
        val web = mutableListOf<WebResultItem>()
        val text = StringBuilder()
        var status = MessageStatus.COMPLETE
        var meta = MessageMeta()
        for (m in rows) {
            val mm = meta(m)
            when (m.role) {
                Roles.ASSISTANT -> if (!m.hidden) {
                    // Live text only while the row is still streaming: a finished row is the source of truth.
                    val body = (if (m.status == MessageStatus.STREAMING) live[m.id] else null) ?: m.text
                    if (body.isNotBlank()) {
                        if (text.isNotEmpty()) text.append(if (mm.continuationOf != null) "" else "\n\n")
                        text.append(body)
                    }
                    if (mm.providerId != null || mm.modelId != null) meta = mm.copy(attachments = emptyList())
                    else if (mm.finishReason != null) meta = meta.copy(finishReason = mm.finishReason)
                    status = m.status
                }
                Roles.TOOL -> {
                    val o = m.toolCallsJson?.let { runCatching { AppJson.parseToJsonElement(it) as JsonObject }.getOrNull() }
                    val cap = (o?.get("capability") as? JsonPrimitive)?.contentOrNull ?: (o?.get("name") as? JsonPrimitive)?.contentOrNull ?: "outil"
                    val ok = (o?.get("ok") as? JsonPrimitive)?.contentOrNull != "false"
                    val call = (o?.get("toolCallId") as? JsonPrimitive)?.contentOrNull?.let { calls[it] }
                    val detail = Envelopes.unwrap(m.text)
                    parts += MessagePart.ToolResult(cap, label(cap), ok, summarize(cap, ok, detail), detail.take(20_000), kindOf(cap))
                    if (ok) sources += Sources.from(cap, call?.arguments, detail, sources.size)
                    // Stored validated, validated again on display (a row written by an older version, or edited).
                    if (ok && cap == "web.search") web += WebResults.sanitize(mm.webResults)
                }
            }
        }
        if (visible.isEmpty() && text.isEmpty() && parts.isEmpty()) return null
        val body = if (markdown) MessagePart.Markdown(parse(text.toString())) else MessagePart.Plain(text.toString())
        val all = buildList {
            addAll(parts)
            if (text.isNotEmpty() || status == MessageStatus.STREAMING) add(body)
            val rich = web.distinctBy { it.type + "|" + (it.mediaUrl ?: it.url) }.take(WebResults.MAX_ITEMS)
            if (rich.isNotEmpty()) add(MessagePart.WebResults(rich))
            if (sources.isNotEmpty()) add(MessagePart.Citations(sources))
            when (status) {
                MessageStatus.STOPPED -> add(MessagePart.SystemEvent("stopped", "Réponse arrêtée."))
                MessageStatus.INTERRUPTED -> add(MessagePart.SystemEvent("interrupted", "Réponse interrompue (connexion ou redémarrage)."))
                MessageStatus.ERROR -> add(MessagePart.Error("La réponse a échoué."))
            }
            meta.artifactIds.forEach { add(MessagePart.Artifact(it, "Artefact")) }
        }
        val siblings = if (user == null || root == null) emptyList() else children[user.id].orEmpty().filter { it.role != Roles.USER || it.hidden }
        return TimelineItem.Assistant(first, rows.last(), rows, all, text.toString(), sources, status, meta,
            root?.let { r -> variants(r.id, siblings.filter { meta(it.metaJson).compareGroup == null || it.id == r.id }.map { it.id }) }, user?.id)
    }

    private fun meta(json: String?): MessageMeta = json?.let { runCatching { AppJson.decodeFromString(MessageMeta.serializer(), it) }.getOrNull() } ?: MessageMeta()

    /** An answer that belongs to a comparison is shown with its sibling lanes (doc 08 §8.2). */
    private fun groupCompare(items: List<TimelineItem>, children: Map<String, List<MessageNode>>): List<TimelineItem> = items.map { item ->
        if (item !is TimelineItem.Assistant) return@map item
        val group = meta(item.first.metaJson).compareGroup ?: return@map item
        val lanes = children[item.parentUserId ?: return@map item].orEmpty().filter { meta(it.metaJson).compareGroup == group }
        if (lanes.size < 2) return@map item
        TimelineItem.Compare(item.parentUserId, group, lanes.map { n -> if (n.id == item.first.id) item.first else stub(n) }, item.first.id)
    }

    private fun stub(n: MessageNode) = MessageEntity(n.id, "", n.role, "", n.createdAt, hidden = n.hidden, parentId = n.parentId, metaJson = n.metaJson)

    /** One line for a tool card (doc 09 §9.3). */
    fun summarize(cap: String, ok: Boolean, detail: String): String {
        val firstLine = detail.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return (if (ok) "" else "Échec : ") + firstLine.removePrefix("ERREUR : ").take(160)
    }

    /** The safe component for a tool result (doc 07 §7.5): never HTML, only these typed views. */
    fun kindOf(cap: String): String = when {
        cap in setOf("exec.run", "build.run", "test.run", "lint.run", "shell.run") -> "terminal"
        cap in setOf("code.patch.preview", "repo.diff", "review.changes", "code.patch.apply") -> "diff"
        cap.startsWith("web.search") || cap == "web.fetch" -> "sources"
        cap.startsWith("spreadsheet.") || cap == "document.read" || cap == "file.read" || cap == "pdf.read" -> "file"
        else -> "text"
    }
}

/** Removes the untrusted-data envelope for display (the content stays marked as untrusted by its card). */
object Envelopes {
    private val OPEN = Regex("<donnees_non_fiables[^>]*>\\n?")
    private val CLOSE = Regex("\\n?</donnees_non_fiables>")
    fun unwrap(text: String): String = CLOSE.replace(OPEN.replace(text, ""), "").trim()
}

/** Citation objects from the turn's own tool results (doc 03 §3.9) — never parsed from the model's prose. */
object Sources {
    private val SEARCH_HIT = Regex("^\\s*([0-9]{1,3})\\. (.+)$")

    fun from(cap: String, args: String?, detail: String, offset: Int): List<Source> {
        val a = args?.let { runCatching { AppJson.parseToJsonElement(it) as JsonObject }.getOrNull() }
        fun arg(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
        return when {
            cap.startsWith("web.search") -> {
                val lines = detail.lines()
                val out = mutableListOf<Source>()
                var k = 0
                while (k < lines.size && out.size < 10) {
                    val m = SEARCH_HIT.matchEntire(lines[k])
                    if (m != null) {
                        val url = lines.getOrNull(k + 1)?.trim()?.takeIf { it.startsWith("http") }
                        val snippet = lines.getOrNull(k + 2)?.trim().orEmpty()
                        out += Source(offset + out.size + 1, m.groupValues[2].trim().take(200), SafeLinks.sanitize(url), null, snippet.take(300), "web.search", true)
                    }
                    k++
                }
                out
            }
            cap == "web.fetch" -> {
                val url = arg("url")
                val title = detail.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("⚠") }?.take(200) ?: url ?: "Page web"
                listOf(Source(offset + 1, title, SafeLinks.sanitize(url), null, detail.lineSequence().drop(1).joinToString(" ").trim().take(300), "web.fetch", true))
            }
            cap in setOf("document.read", "file.read", "pdf.read", "spreadsheet.read") -> {
                val name = arg("source") ?: arg("path") ?: "document"
                listOf(Source(offset + 1, name.take(200), SafeLinks.sanitize(name.takeIf { it.startsWith("artifact:") }), null, detail.take(300), cap, true))
            }
            else -> emptyList()
        }
    }
}
