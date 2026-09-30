package io.github.artisanguillonrenov.cortana.core.exec

import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** How strongly a command is isolated (doc 03 §16, doc 06 §8). */
enum class SandboxMode(val wire: String) {
    /** Plain child process: cwd confined to the workspace, scrubbed environment, CPU/file limits, timeout. No network isolation. */
    PROCESS("process"),
    /** Filesystem and network namespaces (bubblewrap/unshare) or a container: only the workspace is writable, network off unless allowed. */
    ISOLATED("isolated"),
}

data class ProcessSpec(
    val command: String,
    /** Relative to the workspace root. */
    val cwd: String = ".",
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 120_000,
    val maxOutputBytes: Int = 200_000,
    val network: NetworkMode = NetworkMode.DENY,
    val sandbox: SandboxMode = SandboxMode.PROCESS,
    val cpuSeconds: Int? = null,
    /** Files (globs relative to the workspace) to bring back as verified artifacts. */
    val artifactGlobs: List<String> = emptyList(),
    /** Task that owns produced artifacts. */
    val taskId: String? = null,
)

data class ExecResult(
    /** succeeded | failed | timed_out | cancelled | refused | error */
    val status: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val backend: String,
    val sandbox: String,
    val note: String? = null,
    /** Artifacts registered (and hash-verified) from this run. */
    val artifactIds: List<String> = emptyList(),
) {
    val ok get() = status == "succeeded"
    fun render(maxChars: Int = 12_000): String = buildString {
        append("[$backend · $sandbox] ").append(status).append(exitCode?.let { " (code $it)" } ?: "").append(" en ").append(durationMs).append(" ms")
        note?.let { append("\n").append(it) }
        if (stdout.isNotBlank()) append("\n--- sortie ---\n").append(stdout.takeLast(maxChars))
        if (stderr.isNotBlank()) append("\n--- erreurs ---\n").append(stderr.takeLast(maxChars / 2))
    }
}

/** One interface, several implementations (doc 03 §1): Android local, paired worker. */
interface ExecutionBackend {
    val id: String
    val label: String
    suspend fun capabilities(): WorkerCapabilities
    fun supports(mode: SandboxMode, network: NetworkMode): Boolean
    suspend fun run(w: WorkspaceEntity, spec: ProcessSpec, onLog: (String) -> Unit): ExecResult
}

class ExecutionRefused(message: String) : Exception(message)

/**
 * SandboxManager (doc 03 §16, doc 06 §8): chooses where and how a command may run. Untrusted
 * projects only run fully isolated (filesystem + network) — on a backend that really provides it;
 * read-only projects never run anything; trusted projects may run as plain processes, reported
 * honestly as "network not isolated" when the backend cannot enforce it.
 */
/** [defaultBackend]: the owner's choice in the Dev screen, used when a call names no backend. */
class SandboxManager(private val defaultBackend: () -> String? = { null }, private val backends: () -> List<ExecutionBackend>) {
    data class Choice(val backend: ExecutionBackend, val spec: ProcessSpec, val note: String?)

    fun choose(w: WorkspaceEntity, requested: ProcessSpec, preferred: String? = null): Choice {
        val trust = WorkspaceManager.trustOf(w)
        if (trust == WorkspaceTrust.READ_ONLY) throw ExecutionRefused("Projet en lecture seule : aucune exécution")
        val all = backends()
        val explicit = preferred?.takeIf { it != "auto" }
        val wanted = explicit ?: defaultBackend()?.takeIf { it != "auto" && all.any { b -> b.id == it } }
        val pool = if (wanted != null) all.filter { it.id == wanted }.ifEmpty { throw ExecutionRefused("Moteur d'exécution « $wanted » indisponible") } else all
        return when (trust) {
            WorkspaceTrust.UNTRUSTED, WorkspaceTrust.SYSTEM_PROJECT -> {
                val spec = requested.copy(sandbox = SandboxMode.ISOLATED)
                val b = pool.firstOrNull { it.supports(SandboxMode.ISOLATED, spec.network) }
                    ?: throw ExecutionRefused("Projet ${if (trust == WorkspaceTrust.UNTRUSTED) "non fiable" else "système"} : exécution seulement dans un bac à sable isolé (système de fichiers et réseau). " +
                        "Aucun moteur disponible ne l'offre : appairez un worker avec bubblewrap ou un conteneur, ou marquez le projet « De confiance » si vous l'avez vérifié.")
                Choice(b, spec, null)
            }
            else -> {
                val isolated = pool.firstOrNull { it.supports(SandboxMode.ISOLATED, requested.network) }
                if (isolated != null && requested.network == NetworkMode.DENY) return Choice(isolated, requested.copy(sandbox = SandboxMode.ISOLATED), null)
                val b = pool.firstOrNull { it.supports(SandboxMode.PROCESS, requested.network) } ?: pool.firstOrNull { it.supports(SandboxMode.PROCESS, NetworkMode.ALLOW) }
                    ?: throw ExecutionRefused("Aucun moteur d'exécution disponible")
                val note = if (requested.network == NetworkMode.DENY && !b.supports(SandboxMode.PROCESS, NetworkMode.DENY)) "Réseau non isolé sur ${b.label} (projet de confiance)." else null
                Choice(b, requested.copy(sandbox = SandboxMode.PROCESS), note)
            }
        }
    }
}

/**
 * Local backend on the tablet: `/system/bin/sh` and the system toybox, run as a child process of
 * the app (Android forbids executing binaries the app wrote itself). Confined cwd, scrubbed
 * environment, `ulimit` CPU/file-size limits, timeout, and a kill of the whole process tree.
 * It cannot isolate the network or the filesystem, and says so.
 */
class LocalProcessBackend(
    private val workspaces: WorkspaceManager,
    private val tmpDir: File,
    private val shell: String = defaultShell(),
    private val artifacts: io.github.artisanguillonrenov.cortana.core.dev.ArtifactService? = null,
) : ExecutionBackend {
    override val id = "android-local"
    override val label = "tablette"

    override suspend fun capabilities() = WorkerCapabilities(
        os = "android", arch = System.getProperty("os.arch") ?: "?", cpus = Runtime.getRuntime().availableProcessors(),
        memoryMb = Runtime.getRuntime().maxMemory() / 1024 / 1024, toolchains = listOf("sh", "toybox"), buildSystems = emptyList(),
        sandboxModes = listOf(SandboxMode.PROCESS.wire), networkModes = listOf(NetworkMode.ALLOW), capabilities = listOf("exec"),
    )

    override fun supports(mode: SandboxMode, network: NetworkMode) = mode == SandboxMode.PROCESS && network == NetworkMode.ALLOW

    override suspend fun run(w: WorkspaceEntity, spec: ProcessSpec, onLog: (String) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        val fs = workspaces.fs(w)
        val cwd = fs.resolve(spec.cwd)
        if (!cwd.isDirectory) return@withContext ExecResult("refused", null, "", "Dossier introuvable : ${spec.cwd}", 0, id, "process")
        tmpDir.mkdirs()
        val cpu = spec.cpuSeconds ?: ((spec.timeoutMs / 1000).toInt() + 5)
        val pidFile = File(tmpDir, "pid-" + System.nanoTime())
        // The shell records its own PID ($$): reliable on Android and on any JVM, no reflection.
        val script = "echo $$ > '${pidFile.absolutePath}'; ulimit -t $cpu 2>/dev/null; ulimit -f ${512 * 1024} 2>/dev/null; ${spec.command}"
        val pb = ProcessBuilder(shell, "-c", script).directory(cwd)
        pb.environment().clear()
        pb.environment().putAll(mapOf("PATH" to "/system/bin:/system/xbin:/usr/bin:/bin", "HOME" to fs.root.absolutePath, "TMPDIR" to tmpDir.absolutePath, "LANG" to "C.UTF-8"))
        pb.environment().putAll(spec.env.filterKeys { it.matches(Regex("[A-Z_][A-Z0-9_]*")) && it !in setOf("PATH", "LD_PRELOAD", "LD_LIBRARY_PATH") })
        val start = System.currentTimeMillis()
        val p = try { pb.start() } catch (e: Exception) { return@withContext ExecResult("error", null, "", "Démarrage impossible : ${e.message}", 0, id, "process") }
        var pid: Int? = null
        suspend fun pid(): Int? {
            if (pid == null) { repeat(20) { if (pid == null) { pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull(); if (pid == null) delay(10) } } }
            return pid
        }
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { drain(p.inputStream, spec.maxOutputBytes, onLog) }
                val err = async(Dispatchers.IO) { drain(p.errorStream, spec.maxOutputBytes / 2, onLog) }
                var timedOut = false
                val deadline = start + spec.timeoutMs
                try {
                    while (p.isAlive) {
                        if (System.currentTimeMillis() > deadline) { timedOut = true; killTree(p, pid()); break }
                        delay(50)
                    }
                } catch (e: CancellationException) {
                    // Kill now: the stream readers only finish once the process tree is gone.
                    withContext(NonCancellable) { killTree(p, pid()) }
                    throw e
                }
                val code = if (timedOut) null else p.waitFor()
                val stdout = out.await(); val stderr = err.await()
                val produced = collect(fs, spec)
                ExecResult(
                    status = when { timedOut -> "timed_out"; code == 0 -> "succeeded"; else -> "failed" },
                    exitCode = code, stdout = Redactor.redact(stdout), stderr = Redactor.redact(stderr),
                    durationMs = System.currentTimeMillis() - start, backend = id, sandbox = "process",
                    note = if (timedOut) "Arrêté après ${spec.timeoutMs / 1000} s (processus et enfants tués)." else null,
                    artifactIds = produced,
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { killTree(p, pid()) }
            throw e
        } finally {
            pidFile.delete()
        }
    }

    /** Files matching the requested globs become artifacts (same contract as the worker backend). */
    private suspend fun collect(fs: io.github.artisanguillonrenov.cortana.core.dev.WorkspaceFs, spec: ProcessSpec): List<String> {
        val store = artifacts ?: return emptyList()
        if (spec.artifactGlobs.isEmpty()) return emptyList()
        val regexes = spec.artifactGlobs.map { io.github.artisanguillonrenov.cortana.core.dev.CodeSearch.globToRegex(it) }
        return fs.walk(includeGenerated = true).filter { f -> val rel = fs.relative(f); regexes.any { it.matches(rel) } }.take(50).toList().map { f ->
            store.register(f, "build", fs.relative(f).replace('/', '_'), taskId = spec.taskId, capability = "exec.local", metadata = mapOf("path" to fs.relative(f))).artifactId
        }
    }

    companion object {
        fun defaultShell(): String = listOf("/system/bin/sh", "/bin/sh").first { File(it).exists() }

        private fun drain(s: InputStream, max: Int, onLog: (String) -> Unit): String {
            val sb = StringBuilder()
            s.bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    onLog(l)
                    sb.append(l).append('\n')
                    if (sb.length > max * 2) sb.delete(0, sb.length - max) // keep the tail
                }
            }
            return if (sb.length > max) "…\n" + sb.substring(sb.length - max) else sb.toString()
        }

        /** Kills the process and every descendant (found through /proc), deepest first. */
        fun killTree(p: Process, pid: Int?) {
            if (pid != null) {
                val children = descendants(pid)
                // Top-down in one command: a killed parent cannot start its next command. The shell's own `kill`
                // builtin exists on Android (mksh) and every POSIX sh.
                val targets = (listOf(pid) + children).joinToString(" ")
                runCatching { ProcessBuilder(defaultShell(), "-c", "kill -9 $targets 2>/dev/null").redirectErrorStream(true).start().waitFor() }
            }
            p.destroyForcibly()
        }

        fun descendants(root: Int): List<Int> {
            val parent = File("/proc").listFiles()?.mapNotNull { d ->
                d.name.toIntOrNull()?.let { pid -> runCatching { File(d, "stat").readText().substringAfterLast(')').trim().split(' ')[1].toInt() }.getOrNull()?.let { pid to it } }
            }?.toMap() ?: return emptyList()
            val out = mutableListOf<Int>()
            var frontier = listOf(root)
            while (frontier.isNotEmpty()) {
                frontier = parent.filterValues { it in frontier }.keys.toList()
                out += frontier
            }
            return out
        }
    }
}
