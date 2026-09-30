package io.github.artisanguillonrenov.cortana.core.orchestrator

/**
 * Explicit development commands (Git status/fetch/pull, branch creation, push, tests, build,
 * workspace inspection) recognised deterministically, so that "git status du projet calc" or
 * "crée la branche feature/x et pousse-la" is one dispatcher call instead of a model ↔ tools loop.
 *
 * Matching is strict: the whole sentence must be the command, the project must be named exactly or
 * be the only one, a branch name must be a valid Git reference. Anything else returns null and the
 * request goes to the Planner as before. Pure functions (no Android, no I/O) — unit-tested.
 */
object DevCommands {
    data class Call(val capability: String, val args: Map<String, String>)

    /** [calls] run in order through the dispatcher; the sequence stops at the first failure. */
    data class Command(val id: String, val calls: List<Call>, val workspace: String?)

    private const val REF = "([A-Za-z0-9][A-Za-z0-9._/-]{0,99})"
    /** Optional project clause: "du projet calc", "dans le dépôt calc", "le projet calc", "in repo calc". */
    private const val WS = "(?:\\s+(?:du|dans\\s+le|sur\\s+le|pour\\s+le|le|of|in|on)\\s+(?:projet|dépôt|depot|repo|workspace)\\s+([\\w.-]{1,60}))?"
    private const val LEAD = "(?iu)^(?:cortana[ ,]+)?"
    private const val CREATE = "(?:crée|cree|créer|creer|create)\\s+(?:une\\s+|la\\s+|a\\s+)?(?:nouvelle\\s+|new\\s+)?(?:branche|branch)\\s+"

    private val status = Regex("$LEAD(?:git\\s+status|(?:(?:donne|montre)(?:-moi)?\\s+|affiche\\s+)?(?:l['’])?(?:état|etat|statut|status)\\s+git)$WS$")
    private val fetch = Regex("$LEAD(?:git\\s+fetch|fais\\s+un\\s+(?:git\\s+)?fetch)$WS$")
    private val pull = Regex("$LEAD(?:git\\s+pull|fais\\s+un\\s+(?:git\\s+)?pull)$WS$")
    private val branchCreate = Regex("$LEAD(?:$CREATE|git\\s+(?:checkout\\s+-b|switch\\s+-c)\\s+)$REF$WS$")
    private val branchCreateAndPush = Regex(
        "$LEAD$CREATE$REF$WS\\s*,?\\s+(?:et|puis|and|then)\\s+(?:ensuite\\s+)?(?:pousse|push)(?:-la|\\s+la|\\s+it)?(?:\\s+sur\\s+origin|\\s+to\\s+origin)?$"
    )
    private val push = Regex("$LEAD(?:git\\s+push|pousse|push)(?:\\s+(?:la\\s+|the\\s+)?(?:branche|branch)\\s+$REF)?$WS$")
    private val tests = Regex("$LEAD(?:lance|relance|exécute|execute|run)\\s+(?:les\\s+|the\\s+)?tests?$WS$")
    private val build = Regex("$LEAD(?:(?:lance|exécute|execute|run)\\s+(?:le\\s+|un\\s+|the\\s+)?build|build|compile)$WS$")
    private val inspect = Regex("$LEAD(?:inspecte|inspect)\\s+(?:le\\s+|the\\s+)?(?:projet|dépôt|depot|repo|workspace)(?:\\s+([\\w.-]{1,60}))?$")
    private val listProjects = Regex("$LEAD(?:liste|list|affiche|montre(?:-moi)?)\\s+(?:les\\s+|mes\\s+|my\\s+)?(?:projets|workspaces)$")

    private val forceWords = Regex("(?i)(?:\\bforce\\b|--force|(?:^|\\s)-f(?:\\s|$)|\\bforcé)")

    /**
     * [workspaces] are the existing project names. Returns null whenever the command or one of its
     * parameters is not certain (the Planner then handles the request with the model).
     */
    fun parse(text: String, workspaces: List<String>): Command? {
        val t = text.trim().trimEnd('.', '!', ' ')
        if (t.isEmpty() || t.length > 200 || '\n' in t || '?' in t) return null
        if (forceWords.containsMatchIn(t)) return null
        listProjects.find(t)?.let { return Command("dev.workspace.list", listOf(Call("workspace.open", emptyMap())), null) }

        branchCreateAndPush.find(t)?.let { m ->
            val ref = m.groupValues[1].takeIf(::validRef) ?: return null
            val w = resolve(m.groupValues[2], workspaces) ?: return null
            return Command("dev.branch.create_push", listOf(
                Call("repo.branch.create", mapOf("workspace" to w, "name" to ref)),
                Call("repo.push", mapOf("workspace" to w, "branch" to ref)),
            ), w)
        }
        branchCreate.find(t)?.let { m ->
            val ref = m.groupValues[1].takeIf(::validRef) ?: return null
            val w = resolve(m.groupValues[2], workspaces) ?: return null
            return single("dev.branch.create", "repo.branch.create", w, "name" to ref)
        }
        status.find(t)?.let { m -> return single("dev.git.status", "repo.status", resolve(m.groupValues[1], workspaces) ?: return null) }
        fetch.find(t)?.let { m -> return single("dev.git.fetch", "repo.fetch", resolve(m.groupValues[1], workspaces) ?: return null) }
        pull.find(t)?.let { m -> return single("dev.git.pull", "repo.pull", resolve(m.groupValues[1], workspaces) ?: return null) }
        push.find(t)?.let { m ->
            val ref = m.groupValues[1]
            if (ref.isNotEmpty() && !validRef(ref)) return null
            val w = resolve(m.groupValues[2], workspaces) ?: return null
            return if (ref.isEmpty()) single("dev.git.push", "repo.push", w) else single("dev.git.push", "repo.push", w, "branch" to ref)
        }
        tests.find(t)?.let { m -> return single("dev.test.run", "test.run", resolve(m.groupValues[1], workspaces) ?: return null) }
        build.find(t)?.let { m -> return single("dev.build.run", "build.run", resolve(m.groupValues[1], workspaces) ?: return null) }
        inspect.find(t)?.let { m -> return single("dev.workspace.inspect", "workspace.inspect", resolve(m.groupValues[1], workspaces) ?: return null) }
        return null
    }

    private fun single(id: String, capability: String, workspace: String, vararg extra: Pair<String, String>) =
        Command(id, listOf(Call(capability, mapOf("workspace" to workspace) + extra)), workspace)

    /** Named project: exact (case-insensitive) match of one existing name. Unnamed: only when exactly one project exists. */
    fun resolve(named: String, workspaces: List<String>): String? {
        if (named.isNotEmpty()) return workspaces.filter { it.equals(named, ignoreCase = true) }.singleOrNull()
        return workspaces.singleOrNull()
    }

    /** A subset of `git check-ref-format` rules, enough to refuse anything surprising. */
    fun validRef(ref: String): Boolean =
        ref.isNotEmpty() && ref.length <= 100 && !ref.startsWith("-") && !ref.startsWith("/") && !ref.endsWith("/") && !ref.endsWith(".") &&
            !ref.endsWith(".lock") && ".." !in ref && "//" !in ref && "@{" !in ref && "/." !in ref &&
            ref.all { it.isLetterOrDigit() && it.code < 128 || it in "._/-" } &&
            ref.lowercase() != "head"
}
