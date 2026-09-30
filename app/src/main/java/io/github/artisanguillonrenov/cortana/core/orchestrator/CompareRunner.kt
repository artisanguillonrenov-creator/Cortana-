package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.core.chat.ChatStreamHub
import io.github.artisanguillonrenov.cortana.core.chat.MessageMeta
import io.github.artisanguillonrenov.cortana.core.context.ContextEngine
import io.github.artisanguillonrenov.cortana.core.context.ContextRequest
import io.github.artisanguillonrenov.cortana.core.memory.ConversationRepository
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.model.ModelGateway
import io.github.artisanguillonrenov.cortana.core.model.ModelRoute
import io.github.artisanguillonrenov.cortana.core.observability.Tracer
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Comparison mode (doc 08 §8.2, D-20260930-068): the same request answered by 2 to 4 models in
 * parallel, shown side by side. A delegated sub-operation of the one orchestrator (one task, one STOP,
 * one budget, one trace); every call goes through the ModelGateway, without tools — a comparison never
 * multiplies an action. Each answer is a sibling of the owner's message; one lane can be stopped alone.
 */
class CompareRunner(
    private val gateway: ModelGateway,
    private val context: ContextEngine,
    private val conversations: ConversationRepository,
    private val hub: ChatStreamHub?,
    private val tracer: Tracer,
) {
    data class Lane(val lane: Int, val messageId: String, val route: ModelRoute?, val status: String)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Stops one lane of a running comparison (its partial text is kept, marked stopped). */
    fun stopLane(runId: String, lane: Int): Boolean {
        val key = "$runId/$lane"
        stopped += key
        return jobs[key]?.let { it.cancel(CancellationException("lane stop")); true } ?: false
    }

    suspend fun run(tr: TaskRun, userMessageId: String, refs: List<String>, resolve: suspend (String) -> ModelRoute?, onCall: () -> Unit): List<Lane> {
        val runId = tr.runId
        val group = Ids.new().take(8)
        // Every lane exists from the start (the timeline shows the comparison at once); the first one is followed.
        val prepared = refs.distinct().take(4).mapIndexed { i, ref ->
            val lane = i + 1
            val route = resolve(ref)
            val meta = MessageMeta(compareGroup = group, lane = lane, providerId = route?.providerId ?: ref.substringBefore('/'),
                modelId = route?.modelId ?: ref.substringAfter('/'), providerName = route?.providerName)
            val metaJson = AppJson.encodeToString(MessageMeta.serializer(), meta)
            val id = runId?.let { hub?.open(it, parentId = userMessageId, metaJson = metaJson, lane = lane, moveLeaf = false) } ?: Ids.new()
            conversations.streamSnapshot(tr.session.id, id, "", runId, tr.taskId, userMessageId, metaJson, moveLeaf = lane == 1)
            Triple(lane, route, id) to meta
        }
        val results = supervisorScope {
            val calls = prepared.map { (p, meta) ->
                val (lane, route, id) = p
                async {
                    val key = "$runId/$lane"
                    currentCoroutineContext()[Job]?.let { jobs[key] = it }
                    try {
                        if (route == null) {
                            conversations.endStream(id, MessageStatus.ERROR, "Modèle non autorisé ou indisponible (fournisseur désactivé ou mode de confidentialité).")
                            runId?.let { hub?.close(it, id) }
                            return@async Lane(lane, id, null, MessageStatus.ERROR)
                        }
                        val caps = gateway.capabilities.resolve(route.providerId, route.modelId)
                        val built = context.build(ContextRequest(
                            session = tr.session, objective = tr.objective, tools = emptyList(), contextWindow = caps.contextWindow,
                            notes = tr.notes + "Comparaison de modèles : réponds directement au propriétaire, sans appeler d'outil.",
                            currentTaskId = tr.taskId, attachments = tr.attachments.toList(), taintSources = tr.taintSources.toList(),
                        ))
                        onCall()
                        val res = tracer.span("model.compare", tr.taskId, mapOf("gen_ai.request.model" to route.modelId, "compare.lane" to lane.toString())) {
                            gateway.complete(route, built.messages, emptyList(), onDelta = { d -> runId?.let { hub?.delta(it, id, d) } }, role = "compare")
                        }
                        if (res.error != null) {
                            val partial = runId?.let { hub?.textOf(it, id) }.orEmpty()
                            conversations.endStream(id, MessageStatus.ERROR, partial.ifBlank { "Échec : ${res.error.take(300)}" })
                            runId?.let { hub?.close(it, id) }
                            Lane(lane, id, route, MessageStatus.ERROR)
                        } else {
                            conversations.addMessage(tr.session.id, Roles.ASSISTANT, res.text, taskId = tr.taskId, id = id, moveLeaf = false,
                                metaJson = AppJson.encodeToString(MessageMeta.serializer(), meta.copy(finishReason = res.finishReason, providerName = res.route?.providerName ?: meta.providerName)))
                            runId?.let { hub?.close(it, id) }
                            Lane(lane, id, route, MessageStatus.COMPLETE)
                        }
                    } catch (e: CancellationException) {
                        // Only this lane was stopped: keep what it wrote. A global STOP is finished by the orchestrator.
                        if (key in stopped && runId != null) hub?.abortStream(runId, id, MessageStatus.STOPPED)
                        throw e
                    } finally {
                        jobs.remove(key); stopped.remove(key)
                    }
                }
            }
            calls.mapIndexed { i, d ->
                try { d.await() } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e // the whole task was stopped
                    Lane(prepared[i].first.first, prepared[i].first.third, prepared[i].first.second, MessageStatus.STOPPED)
                }
            }
        }
        // The conversation follows the first lane that answered.
        val follow = results.firstOrNull { it.status == MessageStatus.COMPLETE } ?: results.firstOrNull()
        follow?.let { conversations.setLeaf(tr.session.id, it.messageId) }
        return results
    }
}
