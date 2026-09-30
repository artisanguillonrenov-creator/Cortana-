package io.github.artisanguillonrenov.cortana.worker

import io.github.artisanguillonrenov.cortana.contracts.Artifact
import io.github.artisanguillonrenov.cortana.contracts.ArtifactInfo
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.JobStatus
import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.SyncManifest
import io.github.artisanguillonrenov.cortana.contracts.SyncPlan
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.contracts.WorkerJob
import io.github.artisanguillonrenov.cortana.contracts.WorkerJobResult
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Worker-side sandbox (doc 03 §16, doc 06 §8). "isolated" uses Linux user, mount, PID and (unless
 * allowed) network namespaces through `unshare`: the whole system is remounted read-only, only the
 * job's workspace and a private /tmp are writable, the home and worker data directories are hidden,
 * processes cannot see the host's. "process" is a plain child process with a scrubbed environment.
 */
class Sandbox(private val dataDir: File) {
    val isolatedAvailable: Boolean by lazy {
        !isWindows() && runCatching {
            val p = ProcessBuilder("unshare", "-Urmpf", "--mount-proc", "-n", "sh", "-c", "mount -o remount,bind,ro / && true").redirectErrorStream(true).start()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    fun modes(): List<String> = listOfNotNull("process", "isolated".takeIf { isolatedAvailable })

    /**
     * The job's command never travels through process arguments (the JVM encodes those with the
     * platform locale, which corrupts non-ASCII text): it is written as a UTF-8 script in [jobDir].
     */
    fun command(job: WorkerJob, ws: File, cpuSeconds: Long, jobDir: File): Pair<List<String>, Map<String, String>> {
        val limits = "ulimit -t $cpuSeconds 2>/dev/null; ulimit -f ${4L * 1024 * 1024} 2>/dev/null"
        val cmdFile = File(jobDir, "cmd.sh").apply { writeBytes((job.command.joinToString(" ") { shellQuote(it) } + "\n").toByteArray(Charsets.UTF_8)) }
        val path = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
        val env = linkedMapOf("PATH" to path, "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8") + job.env.filterKeys { it.matches(Regex("[A-Z_][A-Z0-9_]*")) && it !in FORBIDDEN_ENV }
        if (isWindows()) return listOf("cmd", "/c", job.command.joinToString(" ")) to (env + ("USERPROFILE" to ws.path))
        if (job.sandbox != "isolated") {
            val run = File(jobDir, "run.sh").apply { writeText("$limits\nexec sh ${shellQuote(cmdFile.absolutePath)}\n") }
            return listOf("sh", run.absolutePath) to (env + mapOf("HOME" to ws.path, "TMPDIR" to ws.path))
        }
        check(isolatedAvailable) { "isolated sandbox unavailable on this worker" }
        val root = File(dataDir, "sandbox-root").apply { mkdirs() }
        val hide = listOfNotNull(System.getProperty("user.home"), dataDir.absolutePath).distinct().joinToString(" ") { shellQuote(it) }
        val setup = """
            set -e
            S=${shellQuote(root.absolutePath)}
            mount -t tmpfs -o size=2048m,mode=755 tmpfs "${'$'}S"
            mkdir -p "${'$'}S/ws" "${'$'}S/home"
            mount --bind ${shellQuote(ws.absolutePath)} "${'$'}S/ws"
            # The private tmpfs (with the workspace inside) becomes /tmp, wherever the data directory lives.
            cp ${shellQuote(cmdFile.absolutePath)} "${'$'}S/.cortana-cmd.sh"
            mount --move "${'$'}S" /tmp
            while read -r _ m _; do
              case "${'$'}m" in /tmp|/tmp/*|/proc|/proc/*|/dev|/dev/*) ;; *) mount -o remount,bind,ro "${'$'}m" 2>/dev/null || true ;; esac
            done < /proc/self/mounts
            for d in $hide; do [ -d "${'$'}d" ] && mount -t tmpfs -o size=1m,mode=700 tmpfs "${'$'}d" || true; done
            cd /tmp/ws
            if [ -n "${'$'}{CORTANA_CWD:-}" ]; then cd "${'$'}CORTANA_CWD"; fi
            export HOME=/tmp/home TMPDIR=/tmp
            set +e
            $limits
            exec sh /tmp/.cortana-cmd.sh
        """.trimIndent()
        val setupFile = File(jobDir, "sandbox.sh").apply { writeBytes(setup.toByteArray(Charsets.UTF_8)) }
        val ns = if (job.network == NetworkMode.ALLOW) "-Urmpf" else "-Urmnpf"
        return listOf("unshare", ns, "--mount-proc", "sh", setupFile.absolutePath) to env
    }

    companion object {
        private val FORBIDDEN_ENV = setOf("PATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "HOME", "DYLD_INSERT_LIBRARIES")
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** Per-device workspace copies with delta sync and zip-slip protection. */
class WorkspaceStore(private val dataDir: File) {
    private val skip = setOf(".git", "build", ".gradle", "node_modules", "target", "dist", ".venv", "__pycache__")

    fun dir(deviceId: String, workspaceId: String): File {
        require(deviceId.matches(SAFE) && workspaceId.matches(SAFE)) { "identifiant invalide" }
        return File(dataDir, "workspaces/$deviceId/$workspaceId").apply { mkdirs() }
    }

    /** Files present and previously synced; the worker's own build outputs are never deleted by a sync. */
    fun plan(deviceId: String, m: SyncManifest): SyncPlan {
        val root = dir(deviceId, m.workspaceId)
        val synced = syncedSet(root)
        val need = m.files.filter { (path, sha) -> val f = safe(root, path); !f.isFile || sha256Hex(f.readBytes()) != sha }.keys.sorted()
        val delete = synced.keys.filter { it !in m.files }.sorted()
        return SyncPlan(need = need, delete = delete)
    }

    fun apply(deviceId: String, workspaceId: String, manifest: SyncManifest, zip: ByteArray): Int {
        val root = dir(deviceId, workspaceId)
        var written = 0
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val f = safe(root, e.name)
                f.parentFile.mkdirs()
                val bytes = z.readBytes()
                val expected = manifest.files[e.name] ?: throw IllegalArgumentException("fichier hors manifeste : ${e.name}")
                require(sha256Hex(bytes) == expected) { "empreinte différente pour ${e.name}" }
                f.writeBytes(bytes)
                written++
            }
        }
        val synced = syncedSet(root)
        for (gone in synced.keys - manifest.files.keys) safe(root, gone).delete()
        File(root, ".cortana-sync.json").writeText(ContractJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), manifest.files))
        return written
    }

    private fun syncedSet(root: File): Map<String, String> = File(root, ".cortana-sync.json").takeIf { it.isFile }
        ?.let { runCatching { ContractJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it.readText()) }.getOrNull() } ?: emptyMap()

    companion object {
        private val SAFE = Regex("[A-Za-z0-9._-]{1,80}")
        /** Zip-slip / traversal protection: the resolved file must stay under [root]. */
        fun safe(root: File, rel: String): File {
            val clean = rel.replace('\\', '/').trimStart('/')
            require(clean.split('/').none { it == ".." || it.isEmpty() }) { "chemin refusé : $rel" }
            val f = File(root, clean).canonicalFile
            require(f.path.startsWith(root.canonicalPath + File.separator)) { "chemin refusé : $rel" }
            return f
        }
    }
}

/**
 * Job execution with durable status, streaming logs, timeout, cancellation of the whole process
 * tree, artifacts hashed on the worker and verified by the tablet, and a lease: a job whose device
 * stops polling for [leaseMs] is cancelled (no orphan work).
 */
class JobManager(
    private val dataDir: File,
    private val sandbox: Sandbox,
    private val workspaces: WorkspaceStore,
    private val maxParallel: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    private val leaseMs: Long = 10 * 60_000L,
) {
    private class Running(val deviceId: String, val job: WorkerJob, @Volatile var process: Process? = null, @Volatile var status: String = "queued",
                          @Volatile var result: WorkerJobResult? = null, @Volatile var lastPoll: Long = System.currentTimeMillis(), @Volatile var cancelled: Boolean = false)

    private val jobs = ConcurrentHashMap<String, Running>()
    private val pool = Executors.newFixedThreadPool(maxParallel) { r -> Thread(r, "cortana-job").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cortana-lease").apply { isDaemon = true } }

    init {
        watchdog.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            jobs.values.filter { it.status == "running" && now - it.lastPoll > leaseMs }.forEach { cancel(it.job.jobId, it.deviceId, "bail expiré : l'appareil ne suit plus la tâche") }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun jobDir(id: String) = File(dataDir, "jobs/$id").apply { mkdirs() }

    fun submit(deviceId: String, job: WorkerJob): JobStatus {
        require(job.jobId.matches(Regex("[A-Za-z0-9._-]{8,80}"))) { "identifiant de tâche invalide" }
        jobs[job.jobId]?.let { existing -> if (existing.deviceId != deviceId) throw AuthException(403, "tâche d'un autre appareil"); return status(job.jobId, deviceId)!! }
        persisted(job.jobId)?.let { if (it.first == deviceId) return JobStatus(jobId = job.jobId, status = it.second.status, result = it.second) }
        if (job.sandbox == "isolated" && !sandbox.isolatedAvailable) throw IllegalStateException("bac à sable isolé indisponible sur ce worker")
        val r = Running(deviceId, job)
        jobs[job.jobId] = r
        pool.submit { execute(r) }
        return JobStatus(jobId = job.jobId, status = "queued")
    }

    private fun execute(r: Running) {
        val job = r.job
        val dir = jobDir(job.jobId)
        val log = File(dir, "log.txt")
        val start = System.currentTimeMillis()
        if (r.cancelled) { finish(r, WorkerJobResult(jobId = job.jobId, status = "cancelled", cancelled = true, sandbox = job.sandbox)); return }
        try {
            val ws = workspaces.dir(r.deviceId, job.workspaceId)
            val cwd = WorkspaceStore.safeDir(ws, job.cwd)
            val (cmd, env) = sandbox.command(job, if (job.sandbox == "isolated") ws else cwd, cpuSeconds = job.timeoutMs / 1000 + 5, jobDir = dir)
            val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            pb.environment().clear(); pb.environment().putAll(env)
            if (job.sandbox == "isolated" && job.cwd != ".") pb.environment()["CORTANA_CWD"] = job.cwd
            r.status = "running"
            val p = pb.start()
            r.process = p
            val finished = p.waitFor(job.timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) killTree(p)
            val code = if (finished) p.exitValue() else null
            val out = tail(log, job.maxOutputBytes)
            val artifacts = if (!r.cancelled) collect(ws, dir, job.artifactGlobs) else emptyList()
            finish(r, WorkerJobResult(
                jobId = job.jobId,
                status = when { r.cancelled -> "cancelled"; !finished -> "timed_out"; code == 0 -> "succeeded"; else -> "failed" },
                exitCode = code, stdout = out, durationMs = System.currentTimeMillis() - start, timedOut = !finished, cancelled = r.cancelled,
                artifacts = artifacts, sandbox = job.sandbox + if (job.sandbox == "isolated" && job.network == NetworkMode.DENY) "+no-network" else "",
            ))
        } catch (e: Exception) {
            finish(r, WorkerJobResult(jobId = job.jobId, status = "error", stdout = tail(log, job.maxOutputBytes), stderr = e.message ?: e.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - start, sandbox = job.sandbox))
        }
    }

    private fun finish(r: Running, result: WorkerJobResult) {
        r.result = result; r.status = result.status
        File(jobDir(r.job.jobId), "job.json").writeText(ContractJson.encodeToString(WorkerJobResult.serializer(), result))
        File(jobDir(r.job.jobId), "device").writeText(r.deviceId)
    }

    private fun persisted(id: String): Pair<String, WorkerJobResult>? {
        val d = File(dataDir, "jobs/$id")
        val res = File(d, "job.json").takeIf { it.isFile } ?: return null
        return runCatching { File(d, "device").readText() to ContractJson.decodeFromString(WorkerJobResult.serializer(), res.readText()) }.getOrNull()
    }

    /**
     * Every job this worker knows (running and persisted) with its device, for administration:
     * the same [JobStatus] the API serves, built without touching the device's polling lease.
     */
    fun list(): List<Pair<String, JobStatus>> {
        val live = jobs.values.map { r -> r.deviceId to JobStatus(jobId = r.job.jobId, status = r.status, logSize = File(dataDir, "jobs/${r.job.jobId}/log.txt").length(), result = r.result) }
        val known = live.map { it.second.jobId }.toSet()
        val stored = File(dataDir, "jobs").listFiles()?.filter { it.isDirectory && it.name !in known }?.mapNotNull { d ->
            persisted(d.name)?.let { (dev, res) -> dev to JobStatus(jobId = d.name, status = res.status, logSize = File(d, "log.txt").length(), result = res) }
        }.orEmpty()
        return (live + stored).sortedBy { it.second.jobId }
    }

    fun status(id: String, deviceId: String): JobStatus? {
        val r = jobs[id]
        if (r == null) {
            val p = persisted(id) ?: return null
            if (p.first != deviceId) throw AuthException(403, "tâche d'un autre appareil")
            return JobStatus(jobId = id, status = p.second.status, logSize = File(dataDir, "jobs/$id/log.txt").length(), result = p.second)
        }
        if (r.deviceId != deviceId) throw AuthException(403, "tâche d'un autre appareil")
        r.lastPoll = System.currentTimeMillis()
        return JobStatus(jobId = id, status = r.status, logSize = File(dataDir, "jobs/$id/log.txt").length(), result = r.result)
    }

    fun log(id: String, deviceId: String, offset: Long, max: Int): Pair<ByteArray, Long> {
        status(id, deviceId) ?: throw AuthException(404, "tâche inconnue")
        val f = File(dataDir, "jobs/$id/log.txt")
        if (!f.isFile || offset >= f.length()) return ByteArray(0) to maxOf(offset, f.length())
        RandomAccessFile(f, "r").use { raf -> raf.seek(offset); val n = minOf(max.toLong(), raf.length() - offset).toInt(); val b = ByteArray(n); raf.readFully(b); return b to offset + n }
    }

    fun cancel(id: String, deviceId: String, reason: String = "annulée par l'appareil"): JobStatus? {
        val r = jobs[id] ?: return status(id, deviceId)
        if (r.deviceId != deviceId) throw AuthException(403, "tâche d'un autre appareil")
        r.cancelled = true
        r.process?.let { killTree(it) }
        File(jobDir(id), "log.txt").appendText("\n[cortana-worker] $reason\n")
        return status(id, deviceId)
    }

    fun artifacts(id: String, deviceId: String): List<ArtifactInfo> =
        (status(id, deviceId)?.result?.artifacts ?: emptyList()).map { ArtifactInfo(it.name, it.sha256, it.sizeBytes) }

    fun artifactFile(id: String, deviceId: String, name: String): File {
        val known = artifacts(id, deviceId).firstOrNull { it.name == name } ?: throw AuthException(404, "artefact inconnu")
        return File(dataDir, "jobs/$id/artifacts/${known.name}")
    }

    private fun collect(ws: File, jobDir: File, globs: List<String>): List<Artifact> {
        if (globs.isEmpty()) return emptyList()
        val regexes = globs.map { globToRegex(it) }
        val out = File(jobDir, "artifacts").apply { mkdirs() }
        return ws.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.mapNotNull { f ->
            val rel = f.relativeTo(ws).path.replace(File.separatorChar, '/')
            if (regexes.none { it.matches(rel) }) return@mapNotNull null
            val name = rel.replace('/', '_')
            val target = File(out, name); f.copyTo(target, overwrite = true)
            Artifact(artifactId = name, type = "build", mime = "application/octet-stream", name = name, uri = "worker:$rel", sha256 = sha256Hex(target.readBytes()),
                sizeBytes = target.length(), createdAt = System.currentTimeMillis(), metadata = mapOf("path" to rel))
        }.take(50).toList()
    }

    fun shutdown() { jobs.values.forEach { r -> r.process?.let { killTree(it) } }; pool.shutdownNow(); watchdog.shutdownNow() }

    companion object {
        fun killTree(p: Process) {
            val h = p.toHandle()
            listOf(h).plus(h.descendants().toList()).forEach { it.destroyForcibly() }
        }

        fun tail(f: File, max: Int): String {
            if (!f.isFile) return ""
            val len = f.length()
            RandomAccessFile(f, "r").use { raf ->
                val start = maxOf(0L, len - max)
                raf.seek(start); val b = ByteArray((len - start).toInt()); raf.readFully(b)
                return (if (start > 0) "…\n" else "") + String(b, Charsets.UTF_8)
            }
        }

        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder(); var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> { sb.append(".*"); i++; if (i + 1 < glob.length && glob[i + 1] == '/') i++ }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString())
        }
    }
}

internal fun WorkspaceStore.Companion.safeDir(root: File, rel: String): File =
    if (rel == "." || rel.isBlank()) root else safe(root, rel).also { require(it.isDirectory) { "dossier introuvable : $rel" } }

/** Toolchains and sandbox modes this machine can offer (doc 03 §17). */
object Capabilities {
    private val probes = linkedMapOf(
        "java" to "java", "javac" to "javac", "gradle" to "gradle", "maven" to "mvn", "node" to "node", "npm" to "npm", "pnpm" to "pnpm",
        "yarn" to "yarn", "bun" to "bun", "python" to "python3", "pip" to "pip3", "uv" to "uv", "poetry" to "poetry", "cargo" to "cargo",
        "go" to "go", "cmake" to "cmake", "make" to "make", "gcc" to "gcc", "clang" to "clang", "git" to "git", "docker" to "docker", "podman" to "podman",
    )

    fun detect(sandbox: Sandbox, maxParallel: Int): WorkerCapabilities {
        val path = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        fun has(bin: String) = path.any { d -> File(d, bin).canExecute() || File(d, "$bin.exe").canExecute() }
        val tools = probes.filter { has(it.value) }.keys.toList()
        val build = buildList {
            if ("gradle" in tools || "java" in tools) add("gradle")
            if ("maven" in tools) add("maven")
            if ("npm" in tools) add("npm"); if ("pnpm" in tools) add("pnpm"); if ("yarn" in tools) add("yarn"); if ("bun" in tools) add("bun")
            if ("python" in tools) add("python"); if ("uv" in tools) add("uv"); if ("poetry" in tools) add("poetry")
            if ("cargo" in tools) add("cargo"); if ("go" in tools) add("go"); if ("cmake" in tools) add("cmake"); if ("make" in tools) add("make")
        }
        val langs = buildList {
            if ("java" in tools) { add("java"); add("kotlin") }
            if ("node" in tools) { add("javascript"); add("typescript") }
            if ("python" in tools) add("python"); if ("cargo" in tools) add("rust"); if ("go" in tools) add("go")
            if ("gcc" in tools || "clang" in tools) { add("c"); add("c++") }
        }
        return WorkerCapabilities(
            os = System.getProperty("os.name"), arch = System.getProperty("os.arch"), cpus = Runtime.getRuntime().availableProcessors(),
            memoryMb = Runtime.getRuntime().maxMemory() / 1024 / 1024, containerRuntime = listOf("docker", "podman").firstOrNull { it in tools },
            toolchains = tools, buildSystems = build, languages = langs, maxParallelJobs = maxParallel, sandboxModes = sandbox.modes(),
            networkModes = if (sandbox.isolatedAvailable) listOf(NetworkMode.DENY, NetworkMode.ALLOW) else listOf(NetworkMode.ALLOW),
            capabilities = listOf("exec", "build", "test", "sync", "artifacts"),
        )
    }
}
