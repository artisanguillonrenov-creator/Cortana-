package io.github.artisanguillonrenov.cortana.core.permissions

import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.GrantService
import io.github.artisanguillonrenov.cortana.core.policy.KillSwitch
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry

/** One Android access Cortana may need, and whether its absence blocks or only degrades a capability. */
enum class Access(val label: String, val why: String) {
    ACCESSIBILITY("Accessibilité", "lire et piloter l'écran"),
    NOTIFICATION_LISTENER("Accès aux notifications", "lire les notifications et y répondre"),
    POST_NOTIFICATIONS("Notifications de Cortana", "afficher rappels, confirmations et résultats"),
    CONTACTS("Contacts", "chercher et gérer les contacts"),
    CALENDAR("Agenda", "lire et modifier les événements"),
    PHONE("Téléphone", "passer des appels"),
    SMS("SMS", "envoyer des SMS"),
    EXACT_ALARMS("Alarmes exactes", "déclencher rappels et tâches à l'heure"),
    WRITE_SETTINGS("Modifier les paramètres système", "régler la luminosité directement"),
    WORKING_FOLDER("Dossier de travail", "lire et écrire vos fichiers"),
    DEVICE_SECURE("Verrouillage de l'appareil", "confirmer les actions sensibles (L3) par empreinte ou code"),
    INSTALL_PACKAGES("Installer des applications", "installer une mise à jour vérifiée"),
    BATTERY("Batterie non restreinte", "ne pas être endormie par One UI"),
}

/** The live state of each access (a seam: Android reads it, tests set it). */
fun interface AccessProbe { fun granted(a: Access): Boolean }

data class Need(val access: Access, val blocking: Boolean)

data class CapabilityRow(
    val capability: String,
    val label: String,
    val category: String,
    val risk: Risk,
    /** available | degraded | unavailable | stopped */
    val status: String,
    val reasons: List<String>,
    val needs: List<Need>,
    val missing: List<Access>,
    val lastUsedAt: Long?,
    val uses: Int,
    val activeGrants: Int,
)

data class AccessRow(val access: Access, val granted: Boolean, val dependents: List<String>, val blocking: Int)

/**
 * Capabilities & permissions (doc 05 §21, phase 32): for every registered capability, its state and
 * the reason, its risk, the Android accesses it depends on (blocking or degrading), the fix to open,
 * its last use, and the owner's standing grants (revocable). Permission revocations are seen live:
 * a capability whose blocking access was withdrawn is shown unavailable, and its executor refuses
 * with a clear message instead of failing obscurely.
 */
class CapabilityHealthService(
    private val registry: ToolRegistry,
    private val db: CortanaDatabase,
    private val probe: AccessProbe,
    private val grants: GrantService,
    private val killSwitch: KillSwitch,
    private val audit: AuditLog,
) {
    fun needs(d: ToolDefinition): List<Need> {
        val c = d.capability
        val out = mutableListOf<Need>()
        fun need(a: Access, blocking: Boolean = true) { out += Need(a, blocking) }
        when {
            c.startsWith("android.ui.") || c.startsWith("android.nav.") -> need(Access.ACCESSIBILITY)
            c.startsWith("notifications.") || c.startsWith("notification.trigger.") -> need(Access.NOTIFICATION_LISTENER)
            c.startsWith("contacts.") -> need(Access.CONTACTS)
            c.startsWith("calendar.") -> need(Access.CALENDAR)
            c.startsWith("phone.") -> need(Access.PHONE)
            c.startsWith("sms.") -> need(Access.SMS)
            c.startsWith("file.") -> need(Access.WORKING_FOLDER)
            c == "android.system.brightness.set" -> need(Access.WRITE_SETTINGS, blocking = false)
        }
        if (c == "schedule.create" || c.startsWith("android.alarm") || c.startsWith("android.timer")) need(Access.EXACT_ALARMS, blocking = false)
        if (c == "schedule.create" || c == "notify.owner") need(Access.POST_NOTIFICATIONS, blocking = c == "notify.owner")
        if (d.baseRisk == Risk.L3 || Trait.FINANCIAL in d.traits || Trait.CREDENTIAL_USE in d.traits || Trait.MODIFIES_SECURITY in d.traits) need(Access.DEVICE_SECURE)
        return out.distinctBy { it.access }
    }

    suspend fun rows(): List<CapabilityRow> {
        val uses = db.tasks().capabilityUses().associateBy { it.capability }
        val halted = killSwitch.isHalted()
        return registry.all().map { d ->
            val needs = needs(d)
            val missing = needs.filter { !probe.granted(it.access) }
            val blocking = missing.filter { it.blocking }
            val grantsN = db.runtime().grantsFor(d.capability).count { g -> g.validUntil == null || g.validUntil > System.currentTimeMillis() }
            val status = when {
                halted && !d.allowWhenHalted -> "stopped"
                blocking.isNotEmpty() -> "unavailable"
                missing.isNotEmpty() -> "degraded"
                else -> "available"
            }
            val reasons = buildList {
                if (status == "stopped") add("Autonomie arrêtée (STOP)")
                missing.forEach { add("${it.access.label} ${if (it.blocking) "requis" else "conseillé"} pour ${it.access.why}") }
                if (grantsN > 0) add("$grantsN autorisation(s) permanente(s) active(s)")
            }
            val u = uses[d.capability]
            CapabilityRow(d.capability, d.label, d.category.name, d.baseRisk, status, reasons, needs, missing.map { it.access }, u?.lastAt, u?.uses ?: 0, grantsN)
        }.sortedWith(compareBy({ it.status == "available" }, { it.category }, { it.capability }))
    }

    suspend fun accesses(): List<AccessRow> {
        val defs = registry.all()
        return Access.entries.map { a ->
            val deps = defs.filter { d -> needs(d).any { it.access == a } }
            AccessRow(a, probe.granted(a), deps.map { it.capability }, deps.count { d -> needs(d).any { it.access == a && it.blocking } })
        }
    }

    /** Owner action: revokes every standing grant of [capability] (audited by the grant service). */
    suspend fun revokeGrants(capability: String): Int {
        val active = db.runtime().grantsFor(capability)
        active.forEach { grants.revoke(it.grantId) }
        if (active.isNotEmpty()) audit.record("owner", "capability.revoke_grants", capability, "ok", """{"count":${active.size}}""")
        return active.size
    }
}
