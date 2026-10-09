package io.github.artisanguillonrenov.cortana.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Chat Workspace contracts (doc 03, 05, 11, 12; D-20260930-068). The Workspace is a presentation layer:
 * these types describe what the owner sees and what the UI asks for. Messages stay in the one
 * conversation store; parts are derived from them deterministically (MessageParts).
 */

/** Conversation modes (doc 00): one screen, the mode only configures the behaviour of the next request. */
@Serializable
enum class ChatMode(val wire: String, val label: String) {
    @SerialName("chat") CHAT("chat", "Discussion"),
    @SerialName("agent") AGENT("agent", "Agent"),
    @SerialName("research") RESEARCH("research", "Recherche"),
    @SerialName("council") COUNCIL("council", "Conseil"),
    @SerialName("compare") COMPARE("compare", "Comparaison"),
    @SerialName("dev") DEV("dev", "Développement"),
    @SerialName("voice") VOICE("voice", "Voix");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: CHAT }
}

/** How an attachment is given to Cortana (doc 02 §2.3). */
@Serializable
enum class AttachmentMode {
    /** Its extracted text goes into the context (enveloped as untrusted data). */
    @SerialName("read") READ,
    /** Cortana is told it exists and analyses it with its document tools. */
    @SerialName("analyze") ANALYZE,
    /** Only referenced: name and type, never read unless asked. */
    @SerialName("reference") REFERENCE,
}

@Serializable
data class AttachmentRef(
    val artifactId: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val mode: AttachmentMode = AttachmentMode.READ,
    /** ready | parsing | failed | unsupported | too_large */
    val status: String = "ready",
    val note: String? = null,
)

/** Structured extras of a message (`messages.metaJson`). Never secrets, never reasoning. */
@Serializable
data class MessageMeta(
    val attachments: List<AttachmentRef> = emptyList(),
    val providerId: String? = null,
    val modelId: String? = null,
    /** Display name of the provider that answered. */
    val providerName: String? = null,
    /** Comparison: the group and lane (1..4) of this answer. */
    val compareGroup: String? = null,
    val lane: Int? = null,
    /** This answer continues [continuationOf] (rendered after it, not as a new bubble). */
    val continuationOf: String? = null,
    /** stop | length | tool_calls | error — from the provider when known. */
    val finishReason: String? = null,
    /** merge | compare_analysis | continuation_prompt | regenerate | edit */
    val kind: String? = null,
    /** An edited user message points to the version it replaces (both are kept). */
    val editedFrom: String? = null,
    /** System events (doc 03 §3.11): model_changed | compacted | file_added | task_created | permission | reconnected | error */
    val event: String? = null,
    /** Artifacts this message produced or references. */
    val artifactIds: List<String> = emptyList(),
    /** Rich web results (images, videos, page cards) a `web.search` row returned — untrusted, validated data. */
    val webResults: List<WebResultItem> = emptyList(),
    /** Images a media tool produced (generated, retouched, transformed): local artifacts shown as pictures. */
    val images: List<AttachmentRef> = emptyList(),
)

/**
 * Images produced by Cortana's media tools, carried as typed data from the tool to the conversation so
 * they are shown as pictures (never as a file to open) and again after a restart. Only these
 * capabilities may produce them; anything that is not a bitmap image is dropped.
 */
object ProducedImages {
    const val DATA_KEY = "producedImages"
    val CAPABILITIES = setOf("media.image.generate", "media.image.edit", "media.image.transform")
    private val ID = Regex("^[A-Za-z0-9-]{8,64}$")
    private val BITMAP = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif")

    fun isBitmap(mime: String): Boolean = mime.lowercase().substringBefore(';').trim() in BITMAP

    fun sanitize(items: List<AttachmentRef>): List<AttachmentRef> =
        items.filter { it.artifactId.matches(ID) && isBitmap(it.mime) }.distinctBy { it.artifactId }.take(8)
}

/** Per-conversation choices (`sessions.settingsJson`). */
@Serializable
data class ChatSessionSettings(
    /** global (follow the default route) | locked (this conversation's model) | project (the project's model). */
    val modelLock: String = "locked",
    /** Memories the owner switched off for this conversation (doc 06 §6.6). */
    val disabledMemoryIds: List<String> = emptyList(),
    /** No memory at all in this conversation's context. */
    val memoryOff: Boolean = false,
    /** Comparison routes ("providerId/modelId"), 2 to 4. */
    val compareRoutes: List<String> = emptyList(),
    /** Inherit the project's instructions and pinned items (doc 06 §6.8). */
    val inheritProject: Boolean = true,
    /** "Réduire le contexte": messages up to this one are only sent as a summary. */
    val compactedUntil: String? = null,
    /** Tool families switched off by the chips of the design for this conversation ([io.github.artisanguillonrenov.cortana.core.tools.ToolFamilies]). */
    val disabledToolFamilies: List<String> = emptyList(),
)

/** Workspace preferences (doc 12). Stored as one settings row, defaults = simple mode. */
@Serializable
data class ChatPrefs(
    /**
     * cortana (the Cortana Workspace design) | workspace (the 2.0.0-rc4 screen) | classic (the 2.0.0-rc3
     * screen); same data. Read through [effectiveUi].
     */
    val ui: String = "cortana",
    /** The owner picked [ui] in the settings; before that, the stored rc4 default "workspace" means the design. */
    val uiChosen: Boolean = false,
    /** compact | comfort | large */
    val density: String = "comfort",
    /** system | light | dark | contrast */
    val theme: String = "system",
    val enterToSend: Boolean = true,
    val confirmDelete: Boolean = true,
    val autoTitle: Boolean = true,
    val autoScroll: Boolean = true,
    // messages
    val markdown: Boolean = true,
    val latex: Boolean = true,
    val mermaid: Boolean = true,
    val codeLineNumbers: Boolean = false,
    val timestamps: Boolean = false,
    val modelBadges: Boolean = true,
    val showSources: Boolean = true,
    // streaming
    val smoothStreaming: Boolean = true,
    val autoResume: Boolean = true,
    val queueWhileGenerating: Boolean = true,
    /** Queued messages older than this need the owner's confirmation before being sent (doc 05 §5.5). */
    val queueRevalidateMinutes: Int = 30,
    /** STOP of the design: "pause" (the task waits, "Reprendre" continues it) or "cancel" (the task ends). */
    val stopAction: String = "pause",
    // context
    val contextMeter: Boolean = true,
    val pins: Boolean = true,
    val projectInheritance: Boolean = true,
    // models
    val favoriteModels: List<String> = emptyList(),
    val recentModels: List<String> = emptyList(),
    val compareDefaults: List<String> = emptyList(),
    // tools
    val showActivity: Boolean = true,
    // files
    val autoParse: Boolean = true,
    val imageMaxDimension: Int = 2048,
    val uploadWarnMb: Int = 20,
    // memory
    val memorySuggestions: Boolean = true,
    // developer
    val developer: Boolean = false,
    // accessibility
    /** Announce "réponse en cours" then the end, never each token (doc 10 §10.7). */
    val announceStreaming: Boolean = true,
    val reduceMotion: Boolean = false,
    /** Reading answers aloud: speech rate (0.5–2.0). */
    val speechRate: Float = 1f,
)

/** The screen actually used: an rc4 default ("workspace" never chosen by the owner) opens the design. */
val ChatPrefs.effectiveUi: String get() = if (ui == "workspace" && !uiChosen) "cortana" else ui

/**
 * How a Workspace action reaches the one orchestrator: keys of `TaskRequest.contextHints`. They only
 * place the turn in the conversation tree and tag its messages; policy, tools and budgets are unchanged.
 */
object ChatHints {
    /** send | edit | regenerate | continue | compare | merge */
    const val KIND = "chat.kind"
    /** Parent of the new user message ("" = a new root): editing or branching from an earlier point. */
    const val PARENT = "chat.parent"
    /** Leaf to move to before answering, without a new visible user message (regenerate, continue). */
    const val LEAF = "chat.leaf"
    /** No visible user message: the turn answers the message at [LEAF]. */
    const val NO_USER_MESSAGE = "chat.noUserMessage"
    /** Hidden instruction added before answering (continuation), never shown to the owner. */
    const val HIDDEN_PROMPT = "chat.hiddenPrompt"
    /** MessageMeta JSON for the owner's message and for the answer. */
    const val USER_META = "chat.userMeta"
    const val ANSWER_META = "chat.answerMeta"
    /** List<AttachmentRef> JSON. */
    const val ATTACHMENTS = "chat.attachments"
    /** Conversation mode for this turn (ChatMode.wire). */
    const val MODE = "chat.mode"
    /** Comparison routes, "providerId/modelId" separated by commas (2 to 4). */
    const val ROUTES = "chat.routes"
}

// ---------------------------------------------------------------- stream events (doc 05 §5.1, doc 11 §11.3)

/** Every event of a generation run carries its run and a strictly increasing sequence: the UI deduplicates and resumes. */
@Serializable
sealed interface ChatStreamEvent {
    val runId: String
    val sequence: Long
    val sessionId: String

    @Serializable @SerialName("queued") data class MessageQueued(override val runId: String, override val sequence: Long, override val sessionId: String, val queueId: String) : ChatStreamEvent
    @Serializable @SerialName("started") data class GenerationStarted(override val runId: String, override val sequence: Long, override val sessionId: String, val taskId: String?) : ChatStreamEvent
    /** Text of [messageId] so far is [full] (deltas are coalesced by the hub; [full] makes a missed delta harmless). */
    @Serializable @SerialName("delta") data class StreamDelta(override val runId: String, override val sequence: Long, override val sessionId: String, val messageId: String, val delta: String, val full: String, val lane: Int? = null) : ChatStreamEvent
    @Serializable @SerialName("status") data class Status(override val runId: String, override val sequence: Long, override val sessionId: String, val text: String) : ChatStreamEvent
    @Serializable @SerialName("tool_started") data class ToolStarted(override val runId: String, override val sequence: Long, override val sessionId: String, val capability: String, val label: String) : ChatStreamEvent
    @Serializable @SerialName("approval") data class ApprovalRequired(override val runId: String, override val sequence: Long, override val sessionId: String, val requestId: String, val action: String, val risk: String) : ChatStreamEvent
    @Serializable @SerialName("tool_done") data class ToolCompleted(override val runId: String, override val sequence: Long, override val sessionId: String, val capability: String, val ok: Boolean, val summary: String) : ChatStreamEvent
    @Serializable @SerialName("artifact") data class ArtifactCreated(override val runId: String, override val sequence: Long, override val sessionId: String, val artifactId: String, val name: String) : ChatStreamEvent
    @Serializable @SerialName("stopped") data class GenerationStopped(override val runId: String, override val sequence: Long, override val sessionId: String, val reason: String) : ChatStreamEvent
    @Serializable @SerialName("completed") data class GenerationCompleted(override val runId: String, override val sequence: Long, override val sessionId: String, val state: String) : ChatStreamEvent
    @Serializable @SerialName("compacted") data class ContextCompacted(override val runId: String, override val sequence: Long, override val sessionId: String, val checkpointId: String, val covered: Int) : ChatStreamEvent
    @Serializable @SerialName("model") data class ModelChanged(override val runId: String, override val sequence: Long, override val sessionId: String, val from: String?, val to: String?) : ChatStreamEvent
    @Serializable @SerialName("branch") data class BranchCreated(override val runId: String, override val sequence: Long, override val sessionId: String, val fromMessageId: String?, val newMessageId: String) : ChatStreamEvent
}

// ---------------------------------------------------------------- message parts (doc 03 §3.1)

/** A citation is an object (doc 03 §3.9): where a fact came from, never text pasted by the model. */
@Serializable
data class Source(
    val index: Int,
    val title: String,
    val url: String? = null,
    val date: String? = null,
    val snippet: String = "",
    /** Capability that fetched it (web.fetch, web.search, document.read…). */
    val provenance: String,
    /** Read from an untrusted source (web, file, MCP): shown as such. */
    val untrusted: Boolean = true,
)

/** Typed parts of a rendered message; the renderer picks a component per type. */
sealed interface MessagePart {
    data class Markdown(val blocks: List<MdBlock>) : MessagePart
    data class Plain(val text: String) : MessagePart
    data class Citations(val sources: List<Source>) : MessagePart
    data class File(val attachment: AttachmentRef) : MessagePart
    data class Image(val attachment: AttachmentRef) : MessagePart
    data class ToolCall(val capability: String, val label: String, val argsSummary: String) : MessagePart
    /** [kind] picks the safe component: text | terminal | diff | table | sources | file. */
    data class ToolResult(val capability: String, val label: String, val ok: Boolean, val summary: String, val detail: String, val kind: String) : MessagePart
    data class Artifact(val artifactId: String, val name: String) : MessagePart
    data class Error(val message: String, val detail: String? = null, val kind: String = "error") : MessagePart
    data class SystemEvent(val kind: String, val text: String) : MessagePart
    data class Council(val runId: String, val summaryJson: String) : MessagePart
    /** Images, videos and page cards from web searches of the turn (external content, typed views only). */
    data class WebResults(val items: List<WebResultItem>) : MessagePart
}
