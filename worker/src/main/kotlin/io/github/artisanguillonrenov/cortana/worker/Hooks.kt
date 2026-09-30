package io.github.artisanguillonrenov.cortana.worker

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.HookEvent
import io.github.artisanguillonrenov.cortana.contracts.HookEvents
import io.github.artisanguillonrenov.cortana.contracts.HookInfo
import io.github.artisanguillonrenov.cortana.contracts.HookRegistration
import io.github.artisanguillonrenov.cortana.contracts.WebhookSignature
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Inbound webhooks hosted for paired tablets (blueprint §37.2). A tablet registers a named hook
 * with its secret; anyone may POST to `/hooks/{hookId}`, but only a correctly signed, fresh,
 * non-replayed request within the hook's rate is queued. The tablet collects its events over the
 * paired channel and acknowledges them. Hooks and pending events survive a worker restart.
 */
class HookStore(dataDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Hook(val hookId: String, val deviceId: String, val name: String, val secret: String, val maxPerMinute: Int, val label: String = "")

    @Serializable
    private data class Stored(val deviceId: String, val event: HookEvent)

    private val dir = File(dataDir, "hooks").apply { mkdirs() }
    private val hooksFile = File(dir, "hooks.json")
    private val eventsFile = File(dir, "events.json")
    private val hooks = LinkedHashMap<String, Hook>()
    private val events = ArrayList<Stored>()
    private val recentSignatures = LinkedHashSet<String>()
    private val windows = HashMap<String, ArrayDeque<Long>>()
    private var seq = 0L

    init {
        runCatching { ContractJson.decodeFromString(ListSerializer(Hook.serializer()), hooksFile.readText()).forEach { hooks[it.hookId] = it } }
        runCatching { events += ContractJson.decodeFromString(ListSerializer(Stored.serializer()), eventsFile.readText()) }
        seq = events.maxOfOrNull { it.event.seq } ?: 0L
    }

    private fun save() {
        hooksFile.privateWrite(ContractJson.encodeToString(ListSerializer(Hook.serializer()), hooks.values.toList()))
        eventsFile.privateWrite(ContractJson.encodeToString(ListSerializer(Stored.serializer()), events))
    }

    @Synchronized fun register(deviceId: String, name: String, reg: HookRegistration): HookInfo {
        require(name.matches(Regex("[a-z0-9][a-z0-9._-]{0,62}"))) { "nom de webhook invalide" }
        val secret = runCatching { unb64(reg.secret) }.getOrNull()
        require(secret != null && secret.size >= 16) { "secret trop court (16 octets au moins)" }
        val existing = hooks.values.firstOrNull { it.deviceId == deviceId && it.name == name }
        val h = Hook(existing?.hookId ?: ("h-" + randomToken(24)), deviceId, name, reg.secret, reg.maxPerMinute.coerceIn(1, 600), reg.label.take(80))
        hooks[h.hookId] = h
        save()
        return HookInfo(h.hookId, "/hooks/${h.hookId}")
    }

    @Synchronized fun delete(deviceId: String, name: String): Boolean {
        val h = hooks.values.firstOrNull { it.deviceId == deviceId && it.name == name } ?: return false
        hooks.remove(h.hookId)
        events.removeAll { it.event.hookId == h.hookId }
        save()
        return true
    }

    /** Public entry point. Returns the HTTP status and a short reason. */
    @Synchronized fun receive(hookId: String, header: (String) -> String?, body: ByteArray): Pair<Int, String> {
        val h = hooks[hookId] ?: return 404 to "inconnu"
        if (body.size > WebhookSignature.MAX_BODY) return 413 to "corps trop volumineux"
        val now = clock()
        WebhookSignature.verify(unb64(h.secret), header, body, now / 1000)?.let { return 401 to it }
        // Replays: the same signature (or delivery id) is accepted once.
        val replayKey = header("X-GitHub-Delivery")?.let { "d:$hookId:$it" } ?: "s:" + (header(WebhookSignature.HEADER) ?: header(WebhookSignature.GITHUB))
        if (replayKey in recentSignatures) return 409 to "déjà reçu"
        val w = windows.getOrPut(hookId) { ArrayDeque() }
        while (w.isNotEmpty() && now - w.first() > 60_000) w.removeFirst()
        if (w.size >= h.maxPerMinute) return 429 to "trop de requêtes"
        if (events.count { it.deviceId == h.deviceId } >= MAX_PENDING) return 503 to "file pleine"
        w.addLast(now)
        recentSignatures += replayKey
        while (recentSignatures.size > 5_000) recentSignatures.remove(recentSignatures.first())
        val kept = listOf("Content-Type", "User-Agent", "X-GitHub-Event", "X-GitHub-Delivery", "X-Cortana-Event").mapNotNull { k -> header(k)?.let { k to it.take(200) } }.toMap()
        events += Stored(h.deviceId, HookEvent(++seq, hookId, h.name, now, kept, body.decodeToString()))
        save()
        return 202 to "accepté"
    }

    /** Events for [deviceId] after [after]; everything up to [after] is acknowledged and dropped. */
    @Synchronized fun collect(deviceId: String, after: Long, max: Int = 50): HookEvents {
        if (events.removeAll { it.deviceId == deviceId && it.event.seq <= after }) save()
        val mine = events.filter { it.deviceId == deviceId }.take(max).map { it.event }
        return HookEvents(mine, mine.lastOrNull()?.seq ?: after)
    }

    /** A revoked device loses its hooks and pending events. */
    @Synchronized fun dropDevice(deviceId: String) {
        val ids = hooks.values.filter { it.deviceId == deviceId }.map { it.hookId }.toSet()
        ids.forEach { hooks.remove(it) }
        events.removeAll { it.deviceId == deviceId }
        save()
    }

    /** Administration view of the hooks: owner, name, public info, rate, pending events — never the secret. */
    @Synchronized fun list(): List<HookSummary> = hooks.values.map { h ->
        HookSummary(h.deviceId, h.name, HookInfo(h.hookId, "/hooks/${h.hookId}"), h.maxPerMinute, h.label, events.count { it.event.hookId == h.hookId })
    }.sortedWith(compareBy({ it.deviceId }, { it.name }))

    @Synchronized fun names(deviceId: String): List<String> = hooks.values.filter { it.deviceId == deviceId }.map { it.name }

    companion object { const val MAX_PENDING = 500 }
}

/** One hook as the administration client shows it (no secret). */
@Serializable
data class HookSummary(val deviceId: String, val name: String, val info: HookInfo, val maxPerMinute: Int, val label: String, val pendingEvents: Int)
