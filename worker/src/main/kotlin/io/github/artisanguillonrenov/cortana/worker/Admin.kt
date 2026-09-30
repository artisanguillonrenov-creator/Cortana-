package io.github.artisanguillonrenov.cortana.worker

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.JobStatus
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Administration and diagnosis of a worker (phase 31): a thin view over the very stores the paired
 * API serves, returning the same contracts ([WorkerCapabilities], [JobStatus], hook info) — no second
 * logic, no private shortcut. Runs on the worker machine only, with the owner's file permissions.
 */
class WorkerAdmin(private val server: WorkerServer) {
    @Serializable
    data class Status(
        val workerId: String,
        val name: String,
        val protocol: String,
        val tlsCertificateSha256: String,
        val isolatedSandbox: Boolean,
        val capabilities: WorkerCapabilities,
        val devices: Int,
        val activeDevices: Int,
        val jobs: Int,
        val runningJobs: Int,
        val hooks: Int,
        val pendingHookEvents: Int,
    )

    @Serializable
    data class JobLine(val deviceId: String, val status: JobStatus)

    fun status(): Status {
        val jobs = server.jobs.list()
        val hooks = server.hooks.list()
        val devices = server.devices.all()
        return Status(
            server.config.workerId, server.config.name, WorkerProtocol.VERSION, server.identity.certificateSha256, server.sandbox.isolatedAvailable,
            server.capabilities, devices.size, devices.count { !it.revoked }, jobs.size, jobs.count { it.second.status in setOf("queued", "running") },
            hooks.size, hooks.sumOf { it.pendingEvents },
        )
    }

    fun devices(): List<PairedDevice> = server.devices.all().sortedBy { it.pairedAt }
    fun jobs(): List<JobLine> = server.jobs.list().map { (d, s) -> JobLine(d, s) }
    fun hooks(): List<HookSummary> = server.hooks.list()

    companion object {
        fun <T> json(s: KSerializer<T>, v: T): String = ContractJson.encodeToString(s, v)
        fun <T> jsonList(s: KSerializer<T>, v: List<T>): String = ContractJson.encodeToString(ListSerializer(s), v)
    }
}
