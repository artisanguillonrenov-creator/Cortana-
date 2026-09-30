package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

class GitRefused(message: String) : Exception(message)

/**
 * GitService (doc 03 §7) on JGit (pure Java; no `git` binary on Android). Reads are free;
 * mutations are explicit and conservative; the dangerous operations of §7 "Interdictions par
 * défaut" are simply not offered (force push, reset --hard, remote branch deletion, amend of a
 * published commit, `.git` deletion) or refused (overwriting unknown local changes, merging into
 * a protected branch without the owner's explicit instruction). Credentials are resolved from the
 * SecretStore by host and never reach the model or the logs.
 */
/**
 * Keeps JGit hermetic: the device's (or a build host's) global and system Git configuration never
 * influence Cortana's repositories (signing, hooks, credential helpers, unknown options…).
 */
class HermeticSystemReader(private val dir: File, private val base: SystemReader) : SystemReader() {
    fun base(): SystemReader = base
    override fun getHostname(): String = base.hostname
    override fun getenv(variable: String?): String? = if (variable?.startsWith("GIT_") == true) null else base.getenv(variable)
    override fun getProperty(key: String?): String? = base.getProperty(key)
    override fun openUserConfig(parent: Config?, fs: FS?) = FileBasedConfig(parent, File(dir, "user.gitconfig"), fs)
    override fun openSystemConfig(parent: Config?, fs: FS?) = FileBasedConfig(parent, File(dir, "system.gitconfig"), fs)
    override fun openJGitConfig(parent: Config?, fs: FS?) = FileBasedConfig(parent, File(dir, "jgit.config"), fs)
    override fun getCurrentTime(): Long = base.currentTime
    override fun getTimezone(`when`: Long): Int = base.getTimezone(`when`)
}

class GitService(
    private val workspaces: WorkspaceManager,
    private val audit: AuditLog,
    private val credentials: (host: String) -> Pair<String, String>?,
    private val identity: () -> PersonIdent,
    private val protectedBranches: () -> Set<String> = { setOf("main", "master") },
    configDir: File? = null,
) : VcsProbe {

    data class Status(val branch: String?, val head: String?, val clean: Boolean, val added: Set<String>, val changed: Set<String>, val modified: Set<String>,
                      val missing: Set<String>, val untracked: Set<String>, val removed: Set<String>, val conflicting: Set<String>, val ahead: Int?, val behind: Int?) {
        val uncommitted get() = added.size + changed.size + modified.size + missing.size + untracked.size + removed.size + conflicting.size
        fun render() = buildString {
            append("Branche ${branch ?: "(détachée)"} · HEAD ${head?.take(10) ?: "(aucun commit)"}")
            if (ahead != null) append(" · en avance $ahead / en retard $behind")
            append('\n')
            if (clean) append("Aucune modification.\n")
            fun sec(label: String, s: Set<String>) { if (s.isNotEmpty()) append(label).append(" : ").append(s.sorted().take(60).joinToString()).append('\n') }
            sec("Indexés (nouveaux)", added); sec("Indexés (modifiés)", changed); sec("Modifiés", modified); sec("Supprimés", missing + removed)
            sec("Non suivis", untracked); sec("En conflit", conflicting)
        }
    }

    data class LogEntry(val id: String, val author: String, val time: Long, val message: String)

    init {
        // Android has no JMX: never let JGit try to register MBeans.
        runCatching { WindowCacheConfig().apply { setExposeStatsViaJmx(false) }.install() }
        configDir?.let { d ->
            d.mkdirs()
            val current = SystemReader.getInstance()
            SystemReader.setInstance(HermeticSystemReader(d, if (current is HermeticSystemReader) current.base() else current))
        }
    }

    private fun root(w: WorkspaceEntity) = File(w.rootPath)
    private fun open(w: WorkspaceEntity): Git = try { Git.open(root(w)) } catch (e: Exception) { throw GitRefused("« ${w.name} » n'est pas un dépôt Git (repo_init ou workspace_create avec git)") }

    // ---------------------------------------------------------------- probe

    override fun snapshot(root: File): VcsProbe.Snapshot? {
        if (!File(root, ".git").exists()) return null
        return runCatching {
            Git.open(root).use { g ->
                val st = g.status().call()
                val head = g.repository.resolve(Constants.HEAD)?.name
                VcsProbe.Snapshot("git", g.repository.branch?.takeIf { head != null || it.isNotEmpty() }, head,
                    st.added.size + st.changed.size + st.modified.size + st.missing.size + st.untracked.size + st.removed.size + st.conflicting.size)
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- init / clone

    suspend fun init(w: WorkspaceEntity, initialBranch: String = "main"): String = withContext(Dispatchers.IO) {
        if (File(root(w), ".git").exists()) throw GitRefused("Déjà un dépôt Git")
        Git.init().setDirectory(root(w)).setInitialBranch(initialBranch).call().use { }
        workspaces.touch(w) { it.copy(vcsType = "git", currentBranch = initialBranch) }
        audit.record("cortana", "repo.init", w.name, "ok")
        initialBranch
    }

    /** Clones [url] into a new untrusted workspace. HTTPS only (file:// only for local worktrees). */
    suspend fun clone(url: String, name: String?, branch: String?): WorkspaceEntity = withContext(Dispatchers.IO) {
        val uri = URIish(url)
        if (uri.scheme != "https") throw GitRefused("Seuls les dépôts https:// peuvent être clonés")
        val w = workspaces.create(name ?: uri.humanishName.ifBlank { "depot" }, origin = "cloned:${redactUrl(url)}", trust = WorkspaceTrust.UNTRUSTED)
        try {
            Git.cloneRepository().setURI(url).setDirectory(root(w)).setCloneAllBranches(false).apply {
                branch?.let { setBranch(it) }
                credentialsFor(url)?.let { setCredentialsProvider(it) }
            }.call().use { g ->
                val head = g.repository.resolve(Constants.HEAD)?.name
                workspaces.touch(w) { it.copy(vcsType = "git", currentBranch = g.repository.branch, baseRevision = head) }
            }
            audit.record("cortana", "repo.clone", redactUrl(url), "ok", """{"workspace":"${w.workspaceId}"}""")
            workspaces.get(w.workspaceId)!!
        } catch (e: Exception) {
            workspaces.delete(w.workspaceId)
            throw GitRefused("Clonage impossible : ${sanitize(e)}")
        }
    }

    // ---------------------------------------------------------------- reads

    suspend fun status(w: WorkspaceEntity): Status = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val st = g.status().call()
            val repo = g.repository
            val head = repo.resolve(Constants.HEAD)?.name
            val tracking = runCatching { org.eclipse.jgit.lib.BranchTrackingStatus.of(repo, repo.branch) }.getOrNull()
            Status(repo.branch, head, st.isClean, st.added, st.changed, st.modified, st.missing, st.untracked, st.removed, st.conflicting,
                tracking?.aheadCount, tracking?.behindCount)
        }
    }

    /** Working tree vs HEAD (default), index vs HEAD ([staged]) or between two revisions. */
    suspend fun diff(w: WorkspaceEntity, staged: Boolean = false, path: String? = null, from: String? = null, to: String? = null, maxBytes: Int = 60_000): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val out = ByteArrayOutputStream()
            val filter = path?.let { org.eclipse.jgit.treewalk.filter.PathFilter.create(it) }
            if (staged && from == null) {
                g.diff().setCached(true).setOutputStream(out).apply { filter?.let { setPathFilter(it) } }.call()
            } else DiffFormatter(out).use { fmt ->
                fmt.setRepository(g.repository)
                fmt.setDiffComparator(RawTextComparator.DEFAULT)
                filter?.let { fmt.pathFilter = it }
                val base = from ?: "HEAD"
                if (g.repository.resolve("$base^{tree}") != null) {
                    fmt.format(fmt.scan(tree(g.repository, base), if (to != null) tree(g.repository, to) else FileTreeIterator(g.repository)))
                }
            }
            out.toString(Charsets.UTF_8.name()).let { if (it.length > maxBytes) it.take(maxBytes) + "\n…[diff tronqué]" else it }
        }
    }

    /** Text of [path] at [rev] (null when absent there or binary). */
    suspend fun fileAt(w: WorkspaceEntity, path: String, rev: String = "HEAD"): String? = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val repo = g.repository
            val treeId = repo.resolve("$rev^{tree}") ?: return@withContext null
            org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, treeId)?.use { tw ->
                val bytes = repo.open(tw.getObjectId(0)).getCachedBytes(8 * 1024 * 1024)
                if (bytes.take(8000).any { it == 0.toByte() }) null else bytes.decodeToString()
            }
        }
    }

    private fun tree(repo: Repository, rev: String): CanonicalTreeParser {
        val id = repo.resolve("$rev^{tree}") ?: throw GitRefused("Révision inconnue : $rev")
        return CanonicalTreeParser().apply { repo.newObjectReader().use { reset(it, id) } }
    }

    suspend fun log(w: WorkspaceEntity, max: Int = 20, path: String? = null): List<LogEntry> = withContext(Dispatchers.IO) {
        open(w).use { g ->
            if (g.repository.resolve(Constants.HEAD) == null) return@withContext emptyList()
            g.log().setMaxCount(max).apply { path?.let { addPath(it) } }.call().map { LogEntry(it.name, it.authorIdent.name, it.commitTime * 1000L, it.fullMessage.trim()) }
        }
    }

    suspend fun show(w: WorkspaceEntity, rev: String): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val repo = g.repository
            val id = repo.resolve(rev) ?: throw GitRefused("Révision inconnue : $rev")
            RevWalk(repo).use { rw ->
                val c = rw.parseCommit(id)
                val parent = c.parents.firstOrNull()?.let { rw.parseCommit(it) }
                val out = ByteArrayOutputStream()
                DiffFormatter(out).use { f -> f.setRepository(repo); f.format(parent?.tree, c.tree) }
                "commit ${c.name}\nAuteur : ${c.authorIdent.name}\nDate : ${java.util.Date(c.commitTime * 1000L)}\n\n${c.fullMessage.trim()}\n\n" + out.toString(Charsets.UTF_8.name()).take(60_000)
            }
        }
    }

    suspend fun branches(w: WorkspaceEntity): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        open(w).use { g ->
            g.branchList().call().map { Repository.shortenRefName(it.name) } to
                g.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call().map { Repository.shortenRefName(it.name) }
        }
    }

    suspend fun tags(w: WorkspaceEntity): List<String> = withContext(Dispatchers.IO) { open(w).use { g -> g.tagList().call().map { Repository.shortenRefName(it.name) } } }

    suspend fun remotes(w: WorkspaceEntity): Map<String, String> = withContext(Dispatchers.IO) {
        open(w).use { g -> g.remoteList().call().associate { it.name to (it.urIs.firstOrNull()?.toString()?.let(::redactUrl) ?: "") } }
    }

    suspend fun blame(w: WorkspaceEntity, path: String): String = withContext(Dispatchers.IO) {
        workspaces.fs(w).resolve(path)
        open(w).use { g ->
            val r = g.blame().setFilePath(path).call() ?: throw GitRefused("Aucun historique pour $path")
            (0 until r.resultContents.size()).joinToString("\n") { i ->
                val c = r.getSourceCommit(i)
                "${c?.name?.take(8) ?: "--------"} ${c?.authorIdent?.name?.take(12)?.padEnd(12) ?: "".padEnd(12)} ${(i + 1).toString().padStart(4)}| ${r.resultContents.getString(i)}"
            }
        }
    }

    suspend fun mergeBase(w: WorkspaceEntity, a: String, b: String): String? = withContext(Dispatchers.IO) {
        open(w).use { g ->
            RevWalk(g.repository).use { rw ->
                rw.revFilter = RevFilter.MERGE_BASE
                rw.markStart(rw.parseCommit(g.repository.resolve(a) ?: throw GitRefused("Révision inconnue : $a")))
                rw.markStart(rw.parseCommit(g.repository.resolve(b) ?: throw GitRefused("Révision inconnue : $b")))
                rw.next()?.name
            }
        }
    }

    /** Linked worktrees are workspaces cloned from this one (JGit has no `git worktree`). */
    suspend fun worktrees(w: WorkspaceEntity): List<WorkspaceEntity> = workspaces.list().filter { it.origin == "worktree:${w.workspaceId}" }

    // ---------------------------------------------------------------- controlled mutations

    suspend fun createBranch(w: WorkspaceEntity, name: String, checkout: Boolean, start: String? = null): String = withContext(Dispatchers.IO) {
        validBranch(name)
        open(w).use { g ->
            g.branchCreate().setName(name).apply { start?.let { setStartPoint(it) } }.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.NOTRACK).call()
            if (checkout) checkout(g, name)
            workspaces.touch(w) { it.copy(currentBranch = g.repository.branch) }
        }
        audit.record("cortana", "repo.branch.create", w.name, "ok", """{"branch":"$name"}""")
        name
    }

    suspend fun switch(w: WorkspaceEntity, branch: String) = withContext(Dispatchers.IO) {
        open(w).use { g -> checkout(g, branch); workspaces.touch(w) { it.copy(currentBranch = g.repository.branch) } }
    }

    private fun checkout(g: Git, branch: String) {
        try {
            val local = g.repository.findRef(Constants.R_HEADS + branch)
            if (local == null && g.repository.findRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                g.checkout().setCreateBranch(true).setName(branch).setStartPoint("origin/$branch").setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
            } else g.checkout().setName(branch).call()
        } catch (e: CheckoutConflictException) {
            throw GitRefused("Changement de branche refusé : des modifications locales seraient écrasées (${e.conflictingPaths.joinToString()}). Committe ou annule-les d'abord.")
        }
    }

    /** Isolated work: a linked clone of this repository on a new branch (doc 03 §7 "Worktrees"). */
    suspend fun createWorktree(w: WorkspaceEntity, branch: String): WorkspaceEntity = withContext(Dispatchers.IO) {
        validBranch(branch)
        val parentHead = open(w).use { it.repository.resolve(Constants.HEAD) } ?: throw GitRefused("Le dépôt n'a encore aucun commit")
        val child = workspaces.create("${w.name} [$branch]", origin = "worktree:${w.workspaceId}", trust = WorkspaceManager.trustOf(w))
        try {
            Git.cloneRepository().setURI(root(w).toURI().toString()).setDirectory(root(child)).call().use { g ->
                g.checkout().setCreateBranch(true).setName(branch).setStartPoint(parentHead.name).call()
                workspaces.touch(child) { it.copy(vcsType = "git", currentBranch = branch, baseRevision = parentHead.name) }
            }
        } catch (e: Exception) { workspaces.delete(child.workspaceId); throw GitRefused("Worktree impossible : ${sanitize(e)}") }
        audit.record("cortana", "repo.worktree.create", w.name, "ok", """{"branch":"$branch","worktree":"${child.workspaceId}"}""")
        workspaces.get(child.workspaceId)!!
    }

    /** Stages [paths] (or everything) and commits. Never amends. Returns the commit id. */
    suspend fun commit(w: WorkspaceEntity, message: String, paths: List<String>? = null): String = withContext(Dispatchers.IO) {
        if (message.isBlank()) throw GitRefused("Message de commit vide")
        open(w).use { g ->
            if (paths.isNullOrEmpty()) { g.add().addFilepattern(".").call(); g.add().addFilepattern(".").setUpdate(true).call() }
            else paths.forEach { p -> workspaces.fs(w).resolve(p); g.add().addFilepattern(p).call(); g.add().addFilepattern(p).setUpdate(true).call() }
            val st = g.status().call()
            if (st.added.isEmpty() && st.changed.isEmpty() && st.removed.isEmpty()) throw GitRefused("Rien à committer")
            val who = identity()
            val c = g.commit().setMessage(message.trim()).setAuthor(who).setCommitter(who).call()
            workspaces.touch(w) { it.copy(currentBranch = g.repository.branch) }
            audit.record("cortana", "repo.commit", w.name, "ok", """{"commit":"${c.name}","branch":"${g.repository.branch}"}""")
            c.name
        }
    }

    suspend fun unstage(w: WorkspaceEntity, paths: List<String>) = withContext(Dispatchers.IO) {
        open(w).use { g -> g.reset().apply { paths.forEach { addPath(it) } }.call() }
    }

    /** Local merge; a protected branch only receives a merge when the owner asked for it explicitly. */
    suspend fun merge(w: WorkspaceEntity, branch: String, ownerInstructed: Boolean): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val target = g.repository.branch
            if (target in protectedBranches() && !ownerInstructed) throw GitRefused("Fusion dans la branche protégée « $target » refusée sans instruction explicite du propriétaire")
            requireClean(g, "fusion")
            val id = g.repository.resolve(branch) ?: throw GitRefused("Branche inconnue : $branch")
            val r = g.merge().include(id).setFastForward(MergeCommand.FastForwardMode.FF).setCommit(true).setMessage("Fusion de $branch dans $target").call()
            when (r.mergeStatus) {
                MergeResult.MergeStatus.CONFLICTING -> {
                    g.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call() // restore the clean pre-merge state we verified above
                    throw GitRefused("Conflits de fusion (${r.conflicts?.keys?.joinToString()}) : fusion annulée, rien n'a changé")
                }
                MergeResult.MergeStatus.FAILED, MergeResult.MergeStatus.ABORTED, MergeResult.MergeStatus.NOT_SUPPORTED -> throw GitRefused("Fusion impossible : ${r.mergeStatus}")
                else -> Unit
            }
            audit.record("cortana", "repo.merge", w.name, "ok", """{"from":"$branch","into":"$target","status":"${r.mergeStatus}"}""")
            "${r.mergeStatus} → ${r.newHead?.name?.take(10)}"
        }
    }

    suspend fun revert(w: WorkspaceEntity, rev: String): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            requireClean(g, "revert")
            val c = g.revert().include(g.repository.resolve(rev) ?: throw GitRefused("Révision inconnue : $rev")).call()
                ?: throw GitRefused("Revert en conflit : rien n'a été committé")
            audit.record("cortana", "repo.revert", w.name, "ok", """{"reverted":"$rev","commit":"${c.name}"}""")
            c.name
        }
    }

    suspend fun cherryPick(w: WorkspaceEntity, rev: String): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            requireClean(g, "cherry-pick")
            val r = g.cherryPick().include(g.repository.resolve(rev) ?: throw GitRefused("Révision inconnue : $rev")).call()
            if (r.status != org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK) {
                g.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
                throw GitRefused("Cherry-pick en conflit : annulé, rien n'a changé")
            }
            r.newHead.name
        }
    }

    suspend fun fetch(w: WorkspaceEntity, remote: String = "origin"): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val url = g.repository.config.getString("remote", remote, "url") ?: throw GitRefused("Dépôt distant « $remote » inconnu")
            val r = g.fetch().setRemote(remote).apply { credentialsFor(url)?.let { setCredentialsProvider(it) } }.call()
            "${r.trackingRefUpdates.size} référence(s) mise(s) à jour"
        }
    }

    /** Pull with an explicit strategy; "ff-only" never creates a merge commit. */
    suspend fun pull(w: WorkspaceEntity, strategy: String, remote: String = "origin"): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            requireClean(g, "pull")
            val url = g.repository.config.getString("remote", remote, "url") ?: throw GitRefused("Dépôt distant « $remote » inconnu")
            val cmd = g.pull().setRemote(remote).apply { credentialsFor(url)?.let { setCredentialsProvider(it) } }
            when (strategy) {
                "ff-only" -> cmd.setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                "merge" -> cmd.setFastForward(MergeCommand.FastForwardMode.FF)
                "rebase" -> cmd.setRebase(true)
                else -> throw GitRefused("Stratégie inconnue : $strategy (ff-only, merge, rebase)")
            }
            val r = cmd.call()
            if (!r.isSuccessful) { g.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call(); throw GitRefused("Pull impossible sans conflit : rien n'a changé") }
            "Pull ($strategy) : ${r.mergeResult?.mergeStatus ?: r.rebaseResult?.status}"
        }
    }

    data class PushPlan(val remote: String, val url: String, val branch: String, val commits: Int, val local: Boolean, val protectedTarget: Boolean)

    /** What a push would do (for the approval screen and the policy classifier). */
    suspend fun pushPlan(w: WorkspaceEntity, remote: String = "origin", branch: String? = null): PushPlan = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val repo = g.repository
            val b = branch ?: repo.branch
            val url = repo.config.getString("remote", remote, "url") ?: throw GitRefused("Dépôt distant « $remote » inconnu")
            val local = url.startsWith("file:") || url.startsWith("/")
            val head = repo.resolve(Constants.R_HEADS + b) ?: throw GitRefused("Branche inconnue : $b")
            // Commits the remote does not have yet: everything reachable from the branch minus every known remote ref.
            val known = repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES + remote + "/").mapNotNull { it.objectId }
            val count = RevWalk(repo).use { rw -> rw.markStart(rw.parseCommit(head)); known.forEach { rw.markUninteresting(rw.parseCommit(it)) }; rw.count() }
            PushPlan(remote, redactUrl(url), b, count, local, b in protectedBranches())
        }
    }

    /** Push one branch, never forced. The caller (policy) decided the approval level. */
    suspend fun push(w: WorkspaceEntity, remote: String = "origin", branch: String? = null): String = withContext(Dispatchers.IO) {
        open(w).use { g ->
            val repo = g.repository
            val b = branch ?: repo.branch
            val url = repo.config.getString("remote", remote, "url") ?: throw GitRefused("Dépôt distant « $remote » inconnu")
            val results = g.push().setRemote(remote).setRefSpecs(RefSpec("${Constants.R_HEADS}$b:${Constants.R_HEADS}$b")).setForce(false)
                .apply { credentialsFor(url)?.let { setCredentialsProvider(it) } }.call()
            val updates = results.flatMap { it.remoteUpdates }
            val bad = updates.filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
            if (bad.isNotEmpty()) throw GitRefused("Push refusé : ${bad.joinToString { "${it.remoteName} ${it.status}" }} (jamais de push forcé)")
            audit.record("cortana", "repo.push", w.name, "ok", """{"remote":"$remote","branch":"$b"}""")
            "Branche $b poussée vers $remote (${redactUrl(url)})"
        }
    }

    /** True when the remote branch already points at the local head (push reconciliation after a crash). */
    suspend fun pushed(w: WorkspaceEntity, remote: String = "origin", branch: String? = null): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            open(w).use { g ->
                val repo = g.repository
                val b = branch ?: repo.branch
                val url = repo.config.getString("remote", remote, "url") ?: return@use null
                val refs = Git.lsRemoteRepository().setRemote(url).setHeads(true).apply { credentialsFor(url)?.let { setCredentialsProvider(it) } }.call()
                val remoteId = refs.firstOrNull { it.name == Constants.R_HEADS + b }?.objectId
                remoteId != null && remoteId == repo.resolve(Constants.R_HEADS + b)
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- helpers

    private fun requireClean(g: Git, op: String) {
        val st = g.status().call()
        if (st.hasUncommittedChanges() || st.conflicting.isNotEmpty()) throw GitRefused("$op refusé : modifications non commitées (${(st.modified + st.changed + st.added + st.removed + st.missing).take(10).joinToString()}). Committe-les d'abord.")
    }

    private fun validBranch(name: String) {
        if (!Repository.isValidRefName(Constants.R_HEADS + name)) throw GitRefused("Nom de branche invalide : $name")
    }

    private fun credentialsFor(url: String): CredentialsProvider? {
        val host = runCatching { URIish(url).host }.getOrNull() ?: return null
        return credentials(host)?.let { (user, token) -> UsernamePasswordCredentialsProvider(user, token) }
    }

    companion object {
        fun redactUrl(url: String): String = url.replace(Regex("//[^/@]+@"), "//")
        fun sanitize(e: Exception): String = redactUrl((e.cause?.message ?: e.message ?: e.javaClass.simpleName)).take(300)
        fun defaultIdentity(name: String = "Cortana", email: String = "cortana@localhost") = PersonIdent(name, email, java.util.Date(), TimeZone.getDefault())
    }
}
