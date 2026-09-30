package io.github.artisanguillonrenov.cortana

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleRunEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.core.permissions.Access
import io.github.artisanguillonrenov.cortana.core.permissions.CapabilityHealthService
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.scheduler.BootReceiver
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.executors.permissions.AndroidAccessProbe
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/** Phase 32 Android lifecycle: permission health and revocation, reboot recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidLifecycleTest : CortanaTestBase() {
    private val granted = mutableSetOf<Access>()
    private fun service() = CapabilityHealthService(c.registry, c.db, { a -> a in granted }, c.grants, c.killSwitch, c.audit)

    @Test fun capabilitiesShowStateReasonFixLastUseAndRevocation() = runBlocking {
        granted += Access.entries.toSet() - setOf(Access.CONTACTS, Access.EXACT_ALARMS)
        c.db.tasks().insertToolCall(ToolCallEntity(Ids.new(), "t", "s", "memory.search", "{}", null, "{}", "ok", "ok", 1_234L))
        c.grants.create("web.fetch")
        val rows = service().rows().associateBy { it.capability }
        val contacts = rows.getValue("contacts.search")
        assertEquals("unavailable", contacts.status)
        assertEquals(listOf(Access.CONTACTS), contacts.missing)
        assertTrue(contacts.reasons.single().contains("Contacts requis"))
        assertEquals("degraded", rows.getValue("schedule.create").status) // alarms only degrade reminders
        assertEquals("available", rows.getValue("memory.search").status)
        assertEquals(1_234L to 1, rows.getValue("memory.search").lastUsedAt to rows.getValue("memory.search").uses)
        assertTrue(rows.values.filter { it.risk == Risk.L3 }.all { r -> r.needs.any { it.access == Access.DEVICE_SECURE } })
        assertEquals(1, rows.getValue("web.fetch").activeGrants)
        assertEquals(1, service().revokeGrants("web.fetch"))
        assertEquals(0, service().rows().single { it.capability == "web.fetch" }.activeGrants)
        val access = service().accesses().single { it.access == Access.CONTACTS }
        assertFalse(access.granted)
        assertTrue(access.dependents.containsAll(listOf("contacts.search", "contacts.read")))
        c.killSwitch.halt("test")
        try { assertEquals("stopped", service().rows().single { it.capability == "web.fetch" }.status) } finally { c.killSwitch.resume("test") }
    }

    @Test fun aRevokedPermissionIsSeenLiveAndTheExecutorRefusesClearly() = runBlocking {
        val probe = AndroidAccessProbe(app, c.settings)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(probe.granted(Access.CONTACTS))
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse(probe.granted(Access.CONTACTS)) // no caching: the next read sees the revocation
        val def = c.registry.byCapability("contacts.search")!!
        val ctx = object : ToolContext {
            override val taskId = "t"; override val sessionId = "s"; override val tainted = false; override val lastUserText = ""; override val incognito = false
            override val approvedRisk = Risk.L1; override val toolset = "full"
            override fun resolveSecret(handle: String?): String? = null; override fun markUiAutomation() {}
        }
        val r = def.invokeAuthorized(AppJson.parseToJsonElement("""{"query":"Durand"}""") as JsonObject, ctx, PolicyDecision(def.capability, Risk.L1, Risk.L1, Requirement.ALLOW, emptyList(), false))
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("non autorisé"))
        assertEquals("unavailable", CapabilityHealthService(c.registry, c.db, probe, c.grants, c.killSwitch, c.audit).rows().single { it.capability == "contacts.search" }.status)
    }

    @Test fun rebootRearmsCatchesUpAndRecovers() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val (reminder, interval) = runBlocking {
            val rem = c.scheduler.create("Médicament", ScheduleKinds.REMINDER, ScheduleSpec(at = now + 3_600_000), ScheduleAction("notify", message = "Prendre le médicament"), zone)
            val iv = c.scheduler.create("Relevé", ScheduleKinds.INTERVAL, ScheduleSpec(everyMinutes = 60, startAt = now + 3_600_000), ScheduleAction("task", objective = "Relevé"), zone, missed = "skip")
            // The tablet was off when both were due.
            c.db.schedules().upsert(rem.copy(nextRunAt = now - 2 * 3_600_000, specJson = ScheduleSpec(at = now - 2 * 3_600_000).toJson()))
            c.db.schedules().upsert(iv.copy(nextRunAt = now - 2 * 3_600_000))
            // A task and a scheduled run were executing when the power went.
            c.db.tasks().upsert(TaskEntity(Ids.new(), "s", "Tâche coupée", "interactive", "running", false, "{}", now - 60_000, updatedAt = now - 60_000, leaseOwner = "proc-avant", leaseExpiresAt = now - 30_000))
            c.db.schedules().upsertRun(ScheduleRunEntity(Ids.new(), iv.id, now - 60_000, now - 60_000, "running", startedAt = now - 60_000))
            rem to iv
        }
        shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.clear()

        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(app, BootReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 10_000
        while (runBlocking { c.db.schedules().get(reminder.id) }!!.lastOutcome == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        runBlocking { c.maintenance.onStartup() } // what the process start does right after boot

        // Missed reminder delivered once, late; the one-shot is done.
        val shown = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(shown.any { it.extras.getString("android.title") == "Rappel (en retard)" && it.extras.getCharSequence("android.text").toString() == "Prendre le médicament" })
        assertFalse(runBlocking { c.db.schedules().get(reminder.id) }!!.enabled)
        // Missed task skipped by its policy, re-armed for its next occurrence.
        val iv = runBlocking { c.db.schedules().get(interval.id) }!!
        assertEquals("manqué (ignoré)", iv.lastOutcome)
        assertTrue(iv.nextRunAt!! > now)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
        // Work cut by the power loss is closed, never silently replayed.
        assertEquals("failed", runBlocking { c.db.tasks().since(0) }.single { it.objective == "Tâche coupée" }.state)
        assertEquals("interrupted", runBlocking { c.db.schedules().runs(interval.id, 5) }.single().status)
        assertNotNull(runBlocking { c.db.audit().allAscending() }.lastOrNull())
    }
}
