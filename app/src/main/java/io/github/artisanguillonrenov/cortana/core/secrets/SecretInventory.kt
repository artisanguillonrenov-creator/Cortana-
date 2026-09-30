package io.github.artisanguillonrenov.cortana.core.secrets

import io.github.artisanguillonrenov.cortana.core.backup.BackupService
import io.github.artisanguillonrenov.cortana.core.backup.SecretAccess
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One use of a secret handle: which owner holds it, and whether the vault still has its value. */
data class SecretRef(val handle: String, val kind: String, val owner: String, val present: Boolean)

/**
 * Secret handles (doc 06 §8, phase 32): the database only ever holds opaque `secret:` handles; the
 * values live encrypted in the vault and are read by the service that owns them. This inventory
 * lists who uses which handle (providers, connections, settings), finds values that are missing or
 * no longer referenced, and rotates a value in place (same handle, audited, value never logged).
 * It never returns a value.
 */
class SecretInventory(private val db: CortanaDatabase, private val vault: SecretAccess, private val audit: AuditLog) {

    suspend fun references(): List<SecretRef> = withContext(Dispatchers.IO) {
        val sdb = db.openHelper.readableDatabase
        val out = mutableListOf<SecretRef>()
        fun add(handle: String, kind: String, owner: String) { out += SecretRef(handle, kind, owner, vault.has(handle)) }
        sdb.query("SELECT displayName, apiKeyHandle FROM providers WHERE apiKeyHandle IS NOT NULL").use { c ->
            while (c.moveToNext()) add(c.getString(1), "fournisseur", c.getString(0))
        }
        sdb.query("SELECT name, secretHandlesJson FROM connections WHERE state != 'revoked'").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                BackupService.HANDLE.findAll(c.getString(1)).forEach { add(it.value, "connexion", name) }
            }
        }
        sdb.query("SELECT key, valueJson FROM settings").use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                walk(runCatching { AppJson.parseToJsonElement(c.getString(1)) }.getOrNull() ?: continue, key) { path, h -> add(h, "réglage", path) }
            }
        }
        out.distinctBy { it.handle to it.owner }.sortedWith(compareBy({ it.kind }, { it.owner }))
    }

    private fun walk(e: JsonElement, path: String, found: (String, String) -> Unit) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> walk(v, "$path.$k", found) }
            is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]", found) }
            is JsonPrimitive -> if (e.isString) BackupService.HANDLE.findAll(e.content).forEach { found(path, it.value) }
        }
    }

    /** Stored values no owner refers to any more (left by a removed provider, a changed setting…). */
    suspend fun orphans(): List<String> {
        val used = references().map { it.handle }.toSet()
        return vault.handles().filter { it !in used }.sorted()
    }

    /** Replaces the value behind a referenced handle; every owner keeps working with the same handle. */
    suspend fun rotate(handle: String, newValue: String) {
        val ref = references().firstOrNull { it.handle == handle } ?: throw IllegalArgumentException("poignée inconnue ou inutilisée")
        require(newValue.isNotBlank()) { "valeur vide" }
        vault.put(handle, newValue.trim())
        Redactor.register(newValue.trim())
        audit.record("owner", "secret.rotate", "${ref.kind} ${ref.owner}", "ok")
    }

    /** Owner action: removes unreferenced values (audited, handles only). */
    suspend fun purgeOrphans(): Int {
        val o = orphans()
        o.forEach { vault.remove(it) }
        if (o.isNotEmpty()) audit.record("owner", "secret.purge_orphans", null, "ok", """{"count":${o.size}}""")
        return o.size
    }
}
