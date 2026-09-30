package io.github.artisanguillonrenov.cortana.core.skills

import io.github.artisanguillonrenov.cortana.contracts.SkillCondition
import io.github.artisanguillonrenov.cortana.contracts.SkillDefinition
import io.github.artisanguillonrenov.cortana.contracts.SkillStep
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.SkillTrajectoryEntity
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Redactor
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Candidate learning (doc 04 §13): after a clean successful task, its acting steps become a
 * trajectory; when the same capability sequence has succeeded [threshold] times, a parameterized
 * candidate skill is proposed (values that differ between runs become parameters, screen node
 * numbers become semantic selectors, secrets become parameters). Never activated automatically.
 */
class SkillLearner(
    private val db: CortanaDatabase,
    private val registry: ToolRegistry,
    private val skills: SkillService,
    private val threshold: () -> Int = { 2 },
) {
    @Serializable
    data class TrajStep(val capability: String, val args: Map<String, String>, val coordinate: Boolean = false, val appPackage: String? = null)

    private val dao get() = db.skills()

    /**
     * Returns the new candidate, if this task completed a repeated procedure. Reading the screen is
     * inherent to UI procedures and allowed; any other untrusted source (web, files…) could have
     * steered the trajectory, so such tasks are never learned from.
     */
    suspend fun observe(taskId: String, objective: String, taintSources: List<String>, now: Long = System.currentTimeMillis()): SkillEntity? {
        if (taintSources.any { !it.startsWith("android.ui.") }) return null
        val calls = db.tasks().toolCalls(taskId)
        if (calls.isEmpty() || calls.any { it.outcome in setOf("error", "denied", "refused") }) return null
        if (calls.any { it.capability in META_REPLAY }) return null // a replayed skill is not a new trajectory
        val messages = db.messages().forSession(db.tasks().get(taskId)?.sessionId ?: return null).filter { it.taskId == taskId }
        val steps = calls.filter { c ->
            val def = registry.byCapability(c.capability) ?: return@filter false
            c.outcome == "ok" && def.sideEffect != SideEffect.NONE && c.capability !in META
        }.map { c ->
            val raw = runCatching { AppJson.parseToJsonElement(c.inputJson).jsonObject }.getOrElse { return null }
            val args = raw.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }.toMutableMap()
            var appPackage: String? = null
            if (c.capability.startsWith("android.ui.")) {
                val screen = lastObservationBefore(messages, c.createdAt)
                appPackage = screen?.packageName
                args["node"]?.toIntOrNull()?.let { n ->
                    val node = screen?.nodes?.get(n) ?: return null // cannot name the target → not learnable
                    args.remove("node")
                    args.putAll(node.selector())
                }
            }
            TrajStep(c.capability, args, coordinate = c.capability == "android.ui.click_point", appPackage = appPackage)
        }
        if (steps.size < 2 || steps.size > MAX_STEPS) return null
        val signature = steps.joinToString(">") { it.capability }
        dao.upsertTrajectory(SkillTrajectoryEntity(taskId, signature, Redactor.redact(objective).take(500),
            AppJson.encodeToString(ListSerializer(TrajStep.serializer()), steps), now))
        if (skills.bySignature(signature) != null) return null
        val trajectories = dao.trajectories(signature, 5)
        if (trajectories.size < threshold()) return null
        val candidate = generalize(signature, trajectories.mapNotNull { t ->
            runCatching { t.objective to AppJson.decodeFromString(ListSerializer(TrajStep.serializer()), t.stepsJson) }.getOrNull()
        }, trajectories.map { it.taskId }, now) ?: return null
        val created = skills.create(candidate, "appris de ${trajectories.size} exécutions réussies")
        skills.validate(created.skillId)
        return skills.get(created.skillId)
    }

    private fun generalize(signature: String, runs: List<Pair<String, List<TrajStep>>>, taskIds: List<String>, now: Long): SkillDefinition? {
        if (runs.isEmpty()) return null
        val shape = runs.first().second
        if (runs.any { it.second.size != shape.size }) return null
        val params = linkedMapOf<String, String>()
        val stepDefs = shape.mapIndexed { i, st ->
            val args = linkedMapOf<String, String>()
            val keys = runs.flatMap { it.second[i].args.keys }.toSortedSet()
            for (k in keys) {
                val values = runs.map { it.second[i].args[k] }
                val same = values.distinct().size == 1 && values.first() != null
                val secret = values.any { it?.contains(Redactor.MASK) == true }
                if (same && !secret) { args[k] = values.first()!!; continue }
                var name = k.replace(Regex("[^a-zA-Z0-9_]"), "_")
                var n = 2
                while (name in params) name = "${k}_${n++}"
                params[name] = if (secret) "valeur secrète à fournir ($k de ${st.capability})" else "$k pour ${st.capability} (ex. « ${values.last()?.take(40)} »)"
                args[k] = "{{$name}}"
            }
            val pre = buildList {
                st.appPackage?.let { add(SkillCondition("package_foreground", it)) }
            }
            SkillStep(i + 1, st.capability, args, preconditions = pre, postconditions = listOf(SkillCondition("tool_succeeded")), coordinateFallback = st.coordinate)
        }
        val caps = shape.map { it.capability }.distinct()
        val appPackage = shape.firstNotNullOfOrNull { it.appPackage }
        // Trigger hints: the owner's own words with parameter values replaced by their names.
        val hints = runs.map { (objective, steps) ->
            var h = objective
            steps.forEachIndexed { i, st -> stepDefs[i].arguments.forEach { (k, v) -> if (v.startsWith("{{")) st.args[k]?.takeIf { it.length >= 3 }?.let { value -> h = h.replace(value, v, ignoreCase = true) } } }
            h.take(200)
        }.distinct()
        val base = runs.last().first.lineSequence().first().take(60).ifBlank { caps.joinToString(" → ") }
        val def = SkillDefinition(
            skillId = "", name = hints.last().take(60).ifBlank { base }, version = 1,
            description = "Procédure apprise : " + stepDefs.joinToString(" → ") { registry.byCapability(it.capability)?.label ?: it.capability },
            triggerHints = hints, parameters = params, requiredCapabilities = caps,
            preconditions = caps.map { SkillCondition("capability_available", it) },
            steps = stepDefs, postconditions = emptyList(),
            appPackage = appPackage, appVersion = null,
            createdFrom = "tasks:" + taskIds.joinToString(","), createdAt = now, updatedAt = now, signature = signature,
        )
        return def.copy(riskLevel = skills.computedRisk(def))
    }

    // ---------------------------------------------------------------- screen observations

    data class ObservedNode(val cls: String, val text: String?, val desc: String?, val id: String?) {
        /** Stable selector, best first (blueprint §17.3): resource id → text → accessibility description. */
        fun selector(): Map<String, String> = when {
            id != null -> mapOf("resource_id" to id)
            !text.isNullOrBlank() -> mapOf("text" to text)
            !desc.isNullOrBlank() -> mapOf("content_description" to desc)
            else -> mapOf("class_name" to cls)
        }
    }

    data class Observation(val packageName: String?, val nodes: Map<Int, ObservedNode>)

    private fun lastObservationBefore(messages: List<MessageEntity>, at: Long): Observation? =
        messages.lastOrNull { m ->
            m.role == Roles.TOOL && m.createdAt <= at &&
                m.toolCallsJson?.let { runCatching { AppJson.parseToJsonElement(it).jsonObject.str("capability") }.getOrNull() } == "android.ui.observe"
        }?.let { parseObservation(it.text) }

    companion object {
        const val MAX_STEPS = 20
        private val META = setOf("tools.discover", "ask_user", "skill.run", "skill.list")
        private val META_REPLAY = setOf("skill.run")
        private val nodeLine = Regex("""^\[(\d+)] (\S+)(?: \[mot de passe])?(?: "((?:[^"\\]|\\.)*)")?(?: desc="((?:[^"\\]|\\.)*)")?(?: id=(\S+))?""")
        private val appLine = Regex("""Application : (\S+)""")

        /** Parses `android.ui.observe` output ("[5] Button "Envoyer" desc="…" id=send (…) [clic]"). */
        fun parseObservation(text: String): Observation {
            val pkg = appLine.find(text)?.groupValues?.get(1)?.takeIf { it != "?" }
            val nodes = text.lineSequence().mapNotNull { l ->
                nodeLine.find(l.trim())?.let { m ->
                    m.groupValues[1].toInt() to ObservedNode(m.groupValues[2], m.groupValues[3].ifEmpty { null }, m.groupValues[4].ifEmpty { null }, m.groupValues[5].ifEmpty { null })
                }
            }.toMap()
            return Observation(pkg, nodes)
        }
    }
}

