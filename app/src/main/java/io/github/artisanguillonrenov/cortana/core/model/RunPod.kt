package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The owner's RunPod account, seen through their API key (Réglages → RunPod, kept in the secret store):
 * list the pods, start a stopped one (an owner-confirmed, paid action), never create or delete one.
 */
class RunPodClient(
    private val client: OkHttpClient,
    private val key: () -> String?,
    private val endpoint: String = "https://api.runpod.io/graphql",
) {
    data class Pod(val id: String, val name: String, val status: String, val costPerHr: Double?, val gpu: String?) {
        val running get() = status.equals("RUNNING", ignoreCase = true)
    }

    class RunPodException(message: String) : Exception(message)

    val configured: Boolean get() = !key().isNullOrBlank()

    private suspend fun graphql(query: String, variables: JsonObject? = null): JsonObject {
        val k = key()?.takeIf { it.isNotBlank() } ?: throw RunPodException("aucune clé API RunPod : ajoutez-la dans Réglages → RunPod")
        val body = buildJsonObject { put("query", query); if (variables != null) put("variables", variables) }.toString()
        val req = Request.Builder().url(endpoint).header("Authorization", "Bearer $k").header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val text = client.newCall(req).await().use { r ->
            if (r.code == 401 || r.code == 403) throw RunPodException("clé API RunPod refusée (HTTP ${r.code}) : vérifiez-la dans Réglages → RunPod")
            if (!r.isSuccessful) throw RunPodException("RunPod HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val o = runCatching { AppJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: throw RunPodException("réponse RunPod illisible")
        (o["errors"] as? JsonArray)?.firstOrNull()?.let { e ->
            val msg = ((e as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "erreur RunPod"
            throw RunPodException(friendly(msg))
        }
        return o["data"] as? JsonObject ?: throw RunPodException("réponse RunPod vide")
    }

    private fun friendly(msg: String): String = when {
        msg.contains("not enough free GPUs", true) || msg.contains("no longer any instances", true) || msg.contains("SUPPLY_CONSTRAINT", true) ->
            "aucun GPU libre pour ce pod en ce moment (la machine du pod est occupée) : réessayez dans quelques minutes, ou utilisez un fournisseur de secours"
        msg.contains("balance", true) || msg.contains("insufficient", true) -> "solde RunPod insuffisant : rechargez votre compte RunPod"
        else -> msg.take(300)
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun pod(o: JsonObject) = Pod(o.s("id").orEmpty(), o.s("name").orEmpty(), o.s("desiredStatus").orEmpty(),
        o.s("costPerHr")?.toDoubleOrNull(), (o["machine"] as? JsonObject)?.s("gpuDisplayName"))

    suspend fun pods(): List<Pod> {
        val d = graphql("query { myself { pods { id name desiredStatus costPerHr machine { gpuDisplayName } } } }")
        val list = ((d["myself"] as? JsonObject)?.get("pods") as? JsonArray).orEmpty()
        return list.mapNotNull { (it as? JsonObject)?.let(::pod) }.filter { it.id.isNotBlank() }
    }

    /** Resumes a stopped pod on its machine (billed while it runs). */
    suspend fun start(podId: String): Pod {
        val d = graphql("mutation(\$input: PodResumeInput!) { podResume(input: \$input) { id name desiredStatus costPerHr machine { gpuDisplayName } } }",
            buildJsonObject { putJsonObject("input") { put("podId", podId); put("gpuCount", 1) } })
        return (d["podResume"] as? JsonObject)?.let(::pod) ?: throw RunPodException("RunPod n'a pas confirmé le démarrage")
    }
}

/** RunPod proxy addresses: https://<pod id>-<port>.proxy.runpod.net/<path>. */
object RunPodAddress {
    data class Parts(val podId: String, val port: Int, val path: String)

    private val re = Regex("^https://([a-z0-9]+)-(\\d{2,5})\\.proxy\\.runpod\\.net(/.*)?$", RegexOption.IGNORE_CASE)

    fun parse(url: String): Parts? = re.find(url.trim())?.let { m -> Parts(m.groupValues[1].lowercase(), m.groupValues[2].toInt(), m.groupValues[3]) }

    fun build(podId: String, port: Int, path: String) = "https://$podId-$port.proxy.runpod.net$path"

    /** RunPod names a migrated pod after the original with "-migration" suffixes. */
    fun baseName(name: String): String = name.trim().replace(Regex("(?i)(-migration)+$"), "")
}

/**
 * Keeps the address of the owner's RunPod providers right when a pod is migrated to another GPU (a new
 * pod, a new id, the same name): the pod is found again by its name, and only a running one is used.
 * A provider whose pod still exists is never changed (an address the owner set keeps working); two
 * running pods with the same name are not guessed between. Every change is audited.
 */
class RunPodResolver(
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
    private val runpod: RunPodClient,
    private val audit: AuditLog,
) {
    data class Change(val providerId: String, val providerName: String, val oldUrl: String, val newUrl: String, val podName: String)
    data class Report(val changes: List<Change> = emptyList(), val problems: List<String> = emptyList(), val stopped: List<RunPodClient.Pod> = emptyList())

    /** Names of the preconfigured pods, known without asking RunPod. */
    private fun knownName(podId: String): String? = when (podId) {
        PreconfiguredPod.POD_ID -> PreconfiguredPod.POD_NAME
        in PreconfiguredPod.OLD_POD_IDS -> "elyndor-5090"
        PreconfiguredPod.CODE_VISION_POD_ID, in PreconfiguredPod.OLD_CODE_VISION_POD_IDS -> PreconfiguredPod.CODE_VISION_POD_NAME
        else -> null
    }

    suspend fun refresh(onlyProviderId: String? = null): Report {
        if (!runpod.configured) return Report()
        val linked = providers.all().filter { (onlyProviderId == null || it.id == onlyProviderId) && RunPodAddress.parse(it.baseUrl) != null }
        if (linked.isEmpty()) return Report()
        val pods = try { runpod.pods() } catch (e: Exception) { return Report(problems = listOf(e.message ?: "RunPod injoignable")) }
        val changes = mutableListOf<Change>()
        val problems = mutableListOf<String>()
        val stopped = mutableListOf<RunPodClient.Pod>()
        val names = settings.current.runpodPodNames.toMutableMap()
        for (p in linked) {
            val parts = RunPodAddress.parse(p.baseUrl) ?: continue
            val current = pods.firstOrNull { it.id.equals(parts.podId, ignoreCase = true) }
            val name = names[p.id] ?: current?.name ?: knownName(parts.podId)
            if (name != null && names[p.id] == null) names[p.id] = RunPodAddress.baseName(name)
            if (current != null) {
                if (!current.running) stopped += current
                continue // the pod still exists: its address is right (running or not)
            }
            if (name == null) { problems += "${p.displayName} : pod introuvable et nom inconnu, corrigez l'adresse à la main"; continue }
            val same = pods.filter { RunPodAddress.baseName(it.name).equals(RunPodAddress.baseName(name), ignoreCase = true) }
            val running = same.filter { it.running }
            val target = when {
                running.size == 1 -> running.single()
                running.size > 1 -> { problems += "${p.displayName} : plusieurs pods « ${RunPodAddress.baseName(name)} » en marche, choisissez l'adresse à la main"; null }
                same.size == 1 -> same.single().also { stopped += it }
                same.isEmpty() -> { problems += "${p.displayName} : aucun pod « ${RunPodAddress.baseName(name)} » sur votre compte RunPod"; null }
                else -> { problems += "${p.displayName} : plusieurs pods « ${RunPodAddress.baseName(name)} » arrêtés, choisissez à la main"; null }
            } ?: continue
            val newUrl = RunPodAddress.build(target.id, parts.port, parts.path)
            providers.update(p.copy(baseUrl = newUrl), null)
            changes += Change(p.id, p.displayName, p.baseUrl, newUrl, target.name)
            audit.record("cortana", "provider.address_updated", p.displayName, "ok",
                buildJsonObject { put("old", p.baseUrl); put("new", newUrl); put("pod", target.name); put("reason", "pod migré ou recréé") }.toString())
            CLog.i("runpod: ${p.displayName} → ${target.id}")
        }
        if (names != settings.current.runpodPodNames) settings.update { it.copy(runpodPodNames = names) }
        return Report(changes, problems, stopped.distinctBy { it.id })
    }

    /** Called by the gateway after a failed call to a RunPod address: the provider with its new address, or null. */
    suspend fun recover(p: ProviderEntity): Pair<ProviderEntity, Change>? {
        if (RunPodAddress.parse(p.baseUrl) == null || !runpod.configured) return null
        val change = refresh(p.id).changes.firstOrNull() ?: return null
        val updated = providers.all().firstOrNull { it.id == p.id } ?: return null
        return updated to change
    }
}
