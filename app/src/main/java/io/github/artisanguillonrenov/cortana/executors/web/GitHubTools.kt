package io.github.artisanguillonrenov.cortana.executors.web

import io.github.artisanguillonrenov.cortana.core.model.await
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GitHub read online, without cloning (owner's request, 2.0.0-rc10): repository, file tree, files,
 * code search, history, pull requests, issues, Actions runs and releases, through the REST API and
 * the guarded web client. Auditing a repository never needs a clone: a clone downloads every object
 * and can exhaust the tablet's memory. Writes (issue, comment, pull request) are external effects,
 * confirmed by the owner (L2) and need the github.com token of Réglages → Git.
 *
 * Every response is untrusted content: it is wrapped as data, never followed as instructions.
 */
class GitHubTools(
    private val client: OkHttpClient,
    /** The github.com token of Réglages → Git, null for anonymous reads (public repositories, 60 requests/hour). */
    private val token: () -> String?,
    private val apiBase: String = "https://api.github.com",
) {
    /** owner/name, plus the ref and path a github.com link may carry (…/tree/REF/PATH, …/blob/REF/PATH). */
    data class RepoRef(val owner: String, val name: String, val ref: String? = null, val path: String? = null) {
        val full get() = "$owner/$name"
    }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_.-]+$")

        fun parseRepo(input: String): RepoRef? {
            val s = input.trim().removeSuffix("/")
            val parts = if (s.contains("github.com", ignoreCase = true)) {
                val afterHost = s.substringAfter("github.com", "").removePrefix(":").removePrefix("/")
                afterHost.split('/').filter { it.isNotEmpty() }
            } else s.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val name = parts[1].removeSuffix(".git")
            if (!owner.matches(NAME) || !name.matches(NAME) || owner.trim('.').isEmpty() || name.trim('.').isEmpty()) return null
            val kind = parts.getOrNull(2)
            return if ((kind == "tree" || kind == "blob") && parts.size >= 4) {
                RepoRef(owner, name, parts[3], parts.drop(4).joinToString("/").ifEmpty { null })
            } else RepoRef(owner, name)
        }

        /** Lines [from]..[to] (1-based, inclusive) numbered like `cat -n`, the text cut at [maxChars]. */
        fun numbered(text: String, from: Int?, to: Int?, maxChars: Int): String {
            val lines = text.lines()
            val start = (from ?: 1).coerceIn(1, maxOf(1, lines.size))
            val end = (to ?: lines.size).coerceIn(start, maxOf(start, lines.size))
            val sb = StringBuilder()
            for (i in start..end) {
                val line = "${i.toString().padStart(5)}  ${lines.getOrElse(i - 1) { "" }}\n"
                if (sb.length + line.length > maxChars) {
                    sb.append("… (coupé à la ligne ${i - 1} sur ${lines.size} : relire avec start_line=$i)\n")
                    break
                }
                sb.append(line)
            }
            return sb.toString()
        }
    }

    private class GitHubError(message: String) : Exception(message)

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.a(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun request(path: String, accept: String = "application/vnd.github+json"): Request.Builder {
        val url = (apiBase.trimEnd('/') + path).toHttpUrlOrNull() ?: throw GitHubError("adresse GitHub invalide : $path")
        return Request.Builder().url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Cortana-Android")
            .apply { token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
    }

    private suspend fun call(req: Request, maxBytes: Long = 2_000_000): String {
        client.newCall(req).await().use { r ->
            val body = r.body?.source()?.let { src -> src.request(maxBytes); src.buffer.clone().readUtf8() }.orEmpty()
            if (r.isSuccessful) return body
            val msg = runCatching { (AppJson.parseToJsonElement(body) as? JsonObject)?.s("message") }.getOrNull()
            throw GitHubError(when {
                r.code == 404 -> "introuvable sur GitHub (dépôt, branche ou fichier inexistant, ou dépôt privé : ajoutez un jeton github.com dans Réglages → Git)"
                r.code == 401 -> "jeton GitHub refusé (Réglages → Git)"
                r.code == 403 || r.code == 429 -> if (r.header("X-RateLimit-Remaining") == "0") "limite de requêtes GitHub atteinte (60/heure sans jeton : ajoutez un jeton github.com dans Réglages → Git)" else "accès refusé par GitHub : ${msg ?: r.code}"
                r.code == 422 -> "requête refusée par GitHub : ${msg ?: "paramètres invalides"}"
                else -> "GitHub HTTP ${r.code}${msg?.let { " : $it" } ?: ""}"
            })
        }
    }

    private suspend fun get(path: String): JsonElement = AppJson.parseToJsonElement(call(request(path).get().build()))

    private suspend fun send(method: String, path: String, body: JsonObject): JsonObject {
        if (token().isNullOrBlank()) throw GitHubError("écriture sur GitHub impossible sans jeton : ajoutez un jeton github.com dans Réglages → Git")
        val req = request(path).method(method, body.toString().toRequestBody("application/json".toMediaType())).build()
        return AppJson.parseToJsonElement(call(req)) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun repoOf(args: JsonObject): RepoRef = args.str("repo")?.let(::parseRepo)
        ?: throw GitHubError("dépôt illisible : donnez « propriétaire/nom » ou un lien https://github.com/propriétaire/nom")

    private suspend fun defaultBranch(r: RepoRef): String = (get("/repos/${r.full}") as JsonObject).s("default_branch") ?: "main"

    private fun enc(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun tool(
        capability: String, label: String, description: String, schema: JsonObject, tags: List<String>,
        write: Boolean = false, run: suspend (JsonObject) -> String,
    ) = ToolDefinition(
        capability = capability, description = description, inputSchema = schema,
        baseRisk = if (write) Risk.L2 else Risk.L1,
        sideEffect = if (write) SideEffect.EXTERNAL else SideEffect.NONE,
        idempotency = if (write) Idempotency.NONE else Idempotency.INTRINSIC,
        dataEgress = DataEgress.EXTERNAL, category = ToolCategory.WEB, label = label,
        maxOutputBytes = 32_000, tags = tags + listOf("github", "dépôt", "repo"),
        extraTraits = if (write) setOf(Trait.USER_VISIBLE_TO_THIRD_PARTY) else emptySet(),
        destinationOf = { "github.com" },
    ) { args, _ ->
        runCatching { ToolResult.ok(run(args), "github:" + (args.str("repo")?.let(::parseRepo)?.full ?: "?")) }
            .getOrElse { ToolResult.error("GitHub : ${it.message}") }
    }

    private val repoArg = "repo" to S.str("Dépôt : « propriétaire/nom » ou lien https://github.com/… (un lien …/tree/BRANCHE/CHEMIN ou …/blob/… donne aussi la branche et le chemin)")
    private val refArg = "ref" to S.str("Branche, tag ou commit (défaut : branche par défaut du dépôt)")
    private val maxArg = "max" to S.int("Nombre maximal d'éléments", 1, 100)

    fun tools(): List<ToolDefinition> = listOf(
        tool("github.repo", "GitHub : dépôt",
            "Fiche d'un dépôt GitHub en ligne, sans le cloner : description, branche par défaut, langages, taille, licence, activité, nombre d'issues et de PR ouvertes. Première étape d'un audit.",
            S.obj(repoArg, required = listOf("repo")), listOf("audit", "auditer", "inspecter", "analyser le dépôt")) { a ->
            val r = repoOf(a)
            val o = get("/repos/${r.full}") as JsonObject
            val langs = (get("/repos/${r.full}/languages") as? JsonObject)?.let { l ->
                val total = l.values.sumOf { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0 }.coerceAtLeast(1)
                l.entries.joinToString { (k, v) -> "$k ${((v as JsonPrimitive).content.toLong() * 100 / total)} %" }
            }
            buildString {
                appendLine("Dépôt ${o.s("full_name")} (${if (o.s("private") == "true") "privé" else "public"})")
                o.s("description")?.let { appendLine("Description : $it") }
                appendLine("Branche par défaut : ${o.s("default_branch")}")
                langs?.takeIf { it.isNotBlank() }?.let { appendLine("Langages : $it") }
                appendLine("Taille : ${o.s("size")} Ko · étoiles ${o.s("stargazers_count")} · forks ${o.s("forks_count")} · issues et PR ouvertes ${o.s("open_issues_count")}")
                o.o("license")?.s("spdx_id")?.let { appendLine("Licence : $it") }
                appendLine("Créé ${o.s("created_at")} · dernier push ${o.s("pushed_at")}")
                o.s("homepage")?.let { appendLine("Site : $it") }
                if (o.s("archived") == "true") appendLine("Archivé (lecture seule).")
                appendLine("Suite : github_tree pour l'arborescence, github_file pour lire un fichier.")
            }
        },
        tool("github.tree", "GitHub : arborescence",
            "Liste les fichiers d'un dépôt GitHub en ligne (chemin et taille), sans cloner. path limite à un dossier.",
            S.obj(repoArg, refArg, "path" to S.str("Dossier à lister (défaut : tout le dépôt)"), maxArg, required = listOf("repo")),
            listOf("fichiers", "arborescence", "structure", "dossiers")) { a ->
            val r = repoOf(a)
            val ref = a.str("ref") ?: r.ref ?: defaultBranch(r)
            val prefix = (a.str("path") ?: r.path)?.trim('/')?.takeIf { it.isNotEmpty() }
            val o = get("/repos/${r.full}/git/trees/${enc(ref)}?recursive=1") as JsonObject
            val all = o.a("tree").filter { prefix == null || it.s("path") == prefix || it.s("path")!!.startsWith("$prefix/") }
            val max = (a.int("max") ?: 100) * 4
            val files = all.count { it.s("type") == "blob" }
            buildString {
                appendLine("${r.full}@$ref${prefix?.let { " /$it" } ?: ""} : $files fichier(s), ${all.size - files} dossier(s)${if (o.s("truncated") == "true") " (liste GitHub tronquée : ciblez un dossier avec path)" else ""}")
                all.take(max).forEach { e ->
                    if (e.s("type") == "tree") appendLine("${e.s("path")}/") else appendLine("${e.s("path")}  (${e.s("size") ?: "?"} o)")
                }
                if (all.size > max) appendLine("… ${all.size - max} autre(s) : ciblez un dossier avec path.")
            }
        },
        tool("github.file", "GitHub : lire un fichier",
            "Lit un fichier d'un dépôt GitHub en ligne, lignes numérotées, sans cloner. start_line/end_line pour lire une partie d'un long fichier.",
            S.obj(repoArg, "path" to S.str("Chemin du fichier dans le dépôt"), refArg,
                "start_line" to S.int("Première ligne (1 = début)", 1, null), "end_line" to S.int("Dernière ligne", 1, null),
                "max_chars" to S.int("Taille maximale (défaut 20000)", 500, 30000), required = listOf("repo")),
            listOf("lire", "fichier", "code source", "contenu")) { a ->
            val r = repoOf(a)
            val path = (a.str("path") ?: r.path)?.trim('/')?.takeIf { it.isNotEmpty() } ?: throw GitHubError("chemin du fichier manquant (path)")
            val ref = a.str("ref") ?: r.ref
            val q = ref?.let { "?ref=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val text = call(request("/repos/${r.full}/contents/${enc(path)}$q", "application/vnd.github.raw").get().build())
            if (text.take(8000).any { it == '\u0000' }) return@tool "$path : fichier binaire (${text.length} o), non affiché."
            "${r.full}@${ref ?: "défaut"} : $path (${text.lines().size} lignes)\n" + numbered(text, a.int("start_line"), a.int("end_line"), a.int("max_chars") ?: 20_000)
        },
        tool("github.search", "GitHub : chercher dans le code",
            "Cherche un texte ou un symbole dans le code d'un dépôt GitHub en ligne (fichiers et extraits). Nécessite un jeton github.com (Réglages → Git) ; sans jeton, utiliser github_tree puis github_file.",
            S.obj(repoArg, "query" to S.str("Texte, nom de fonction ou de classe à chercher"), maxArg, required = listOf("repo", "query")),
            listOf("chercher", "rechercher", "grep", "où est", "trouver dans le code")) { a ->
            val r = repoOf(a)
            if (token().isNullOrBlank()) throw GitHubError("la recherche de code GitHub exige un jeton github.com (Réglages → Git) ; sans jeton, utilisez github_tree puis github_file")
            val q = java.net.URLEncoder.encode("${a.str("query").orEmpty()} repo:${r.full}", "UTF-8")
            val o = AppJson.parseToJsonElement(call(request("/search/code?q=$q&per_page=${a.int("max") ?: 20}", "application/vnd.github.text-match+json").get().build())) as JsonObject
            val items = o.a("items")
            if (items.isEmpty()) return@tool "Aucun résultat pour « ${a.str("query")} » dans ${r.full}."
            buildString {
                appendLine("${o.s("total_count")} résultat(s) pour « ${a.str("query")} » dans ${r.full} :")
                items.forEach { i ->
                    appendLine("- ${i.s("path")}")
                    i.a("text_matches").take(2).forEach { m -> m.s("fragment")?.lines()?.take(4)?.forEach { appendLine("    $it") } }
                }
            }
        },
        tool("github.commits", "GitHub : historique",
            "Historique d'un dépôt GitHub en ligne (option : d'un fichier ou dossier), ou le détail d'un commit (sha) avec les fichiers modifiés et le diff.",
            S.obj(repoArg, refArg, "path" to S.str("Limiter à ce fichier ou dossier"), "sha" to S.str("Détail de ce commit"), maxArg, required = listOf("repo")),
            listOf("commits", "historique", "log", "changements", "diff")) { a ->
            val r = repoOf(a)
            a.str("sha")?.let { sha ->
                val c = get("/repos/${r.full}/commits/${enc(sha)}") as JsonObject
                return@tool buildString {
                    appendLine("Commit ${c.s("sha")} par ${c.o("commit")?.o("author")?.s("name")} le ${c.o("commit")?.o("author")?.s("date")}")
                    appendLine(c.o("commit")?.s("message").orEmpty())
                    c.o("stats")?.let { appendLine("+${it.s("additions")} −${it.s("deletions")}") }
                    var budget = 20_000
                    c.a("files").forEach { f ->
                        appendLine("\n${f.s("status")} ${f.s("filename")} (+${f.s("additions")} −${f.s("deletions")})")
                        f.s("patch")?.let { p -> val part = p.take(budget.coerceAtLeast(0)); budget -= part.length; if (part.isNotEmpty()) appendLine(part) }
                    }
                }
            }
            val params = buildList {
                (a.str("ref") ?: r.ref)?.let { add("sha=" + java.net.URLEncoder.encode(it, "UTF-8")) }
                (a.str("path") ?: r.path)?.let { add("path=" + java.net.URLEncoder.encode(it, "UTF-8")) }
                add("per_page=${a.int("max") ?: 20}")
            }.joinToString("&")
            get("/repos/${r.full}/commits?$params").objects().joinToString("\n") { c ->
                val m = c.o("commit")
                "${c.s("sha")?.take(7)} ${m?.o("author")?.s("date")?.take(10)} ${m?.o("author")?.s("name")} — ${m?.s("message")?.lineSequence()?.firstOrNull().orEmpty()}"
            }.ifEmpty { "Aucun commit." }
        },
        tool("github.branches", "GitHub : branches",
            "Liste les branches d'un dépôt GitHub en ligne, avec leur dernier commit et leur protection.",
            S.obj(repoArg, maxArg, required = listOf("repo")), listOf("branches")) { a ->
            val r = repoOf(a)
            get("/repos/${r.full}/branches?per_page=${a.int("max") ?: 100}").objects().joinToString("\n") { b ->
                "${b.s("name")}  ${b.o("commit")?.s("sha")?.take(7)}${if (b.s("protected") == "true") "  (protégée)" else ""}"
            }.ifEmpty { "Aucune branche." }
        },
        tool("github.pulls", "GitHub : pull requests",
            "Pull requests d'un dépôt GitHub : la liste (state open/closed/all), ou le détail d'une PR (number) avec sa description, son état de fusion et ses fichiers modifiés.",
            S.obj(repoArg, "number" to S.int("Numéro de la PR à détailler", 1, null), "state" to S.str("État des PR listées", listOf("open", "closed", "all")), maxArg, required = listOf("repo")),
            listOf("pull request", "pr", "revue", "fusion")) { a ->
            val r = repoOf(a)
            a.int("number")?.let { n ->
                val p = get("/repos/${r.full}/pulls/$n") as JsonObject
                val files = get("/repos/${r.full}/pulls/$n/files?per_page=100").objects()
                return@tool buildString {
                    appendLine("PR #$n « ${p.s("title")} » par ${p.o("user")?.s("login")} : ${p.s("state")}${if (p.s("merged") == "true") " (fusionnée)" else ""}, ${p.o("head")?.s("ref")} → ${p.o("base")?.s("ref")}")
                    appendLine("Fusionnable : ${p.s("mergeable") ?: "inconnu"} · +${p.s("additions")} −${p.s("deletions")} · ${p.s("changed_files")} fichier(s)")
                    p.s("body")?.let { appendLine("\n${it.take(6000)}") }
                    appendLine("\nFichiers :")
                    files.forEach { f -> appendLine("- ${f.s("status")} ${f.s("filename")} (+${f.s("additions")} −${f.s("deletions")})") }
                }
            }
            get("/repos/${r.full}/pulls?state=${a.str("state") ?: "open"}&per_page=${a.int("max") ?: 20}").objects().joinToString("\n") { p ->
                "#${p.s("number")} ${p.s("state")} « ${p.s("title")} » ${p.o("head")?.s("ref")} → ${p.o("base")?.s("ref")} (${p.o("user")?.s("login")}, ${p.s("updated_at")?.take(10)})"
            }.ifEmpty { "Aucune pull request." }
        },
        tool("github.issues", "GitHub : issues",
            "Issues d'un dépôt GitHub : la liste (state open/closed/all), ou le détail d'une issue (number) avec ses commentaires.",
            S.obj(repoArg, "number" to S.int("Numéro de l'issue à détailler", 1, null), "state" to S.str("État des issues listées", listOf("open", "closed", "all")), maxArg, required = listOf("repo")),
            listOf("issue", "bug", "ticket", "problème signalé")) { a ->
            val r = repoOf(a)
            a.int("number")?.let { n ->
                val i = get("/repos/${r.full}/issues/$n") as JsonObject
                val comments = get("/repos/${r.full}/issues/$n/comments?per_page=50").objects()
                return@tool buildString {
                    appendLine("#$n « ${i.s("title")} » par ${i.o("user")?.s("login")} : ${i.s("state")}")
                    i.s("body")?.let { appendLine(it.take(6000)) }
                    comments.forEach { c -> appendLine("\n— ${c.o("user")?.s("login")} (${c.s("created_at")?.take(10)}) :\n${c.s("body")?.take(3000)}") }
                }
            }
            get("/repos/${r.full}/issues?state=${a.str("state") ?: "open"}&per_page=${a.int("max") ?: 20}").objects()
                .filter { it["pull_request"] == null }
                .joinToString("\n") { i -> "#${i.s("number")} ${i.s("state")} « ${i.s("title")} » (${i.o("user")?.s("login")}, ${i.s("updated_at")?.take(10)})" }
                .ifEmpty { "Aucune issue." }
        },
        tool("github.actions", "GitHub : intégration continue",
            "Exécutions GitHub Actions (CI) d'un dépôt : la liste récente avec leur résultat, ou le détail d'une exécution (run_id) avec ses jobs et les étapes en échec.",
            S.obj(repoArg, "run_id" to S.str("Identifiant de l'exécution à détailler"), "branch" to S.str("Limiter à cette branche"), maxArg, required = listOf("repo")),
            listOf("ci", "actions", "build", "tests", "workflow", "échec")) { a ->
            val r = repoOf(a)
            a.str("run_id")?.let { id ->
                val jobs = (get("/repos/${r.full}/actions/runs/${enc(id)}/jobs?per_page=50") as JsonObject).a("jobs")
                return@tool jobs.joinToString("\n") { j ->
                    val failed = j.a("steps").filter { it.s("conclusion") == "failure" }.joinToString { it.s("name").orEmpty() }
                    "${j.s("name")} : ${j.s("status")} ${j.s("conclusion") ?: ""}${if (failed.isNotEmpty()) " — étape(s) en échec : $failed" else ""}"
                }.ifEmpty { "Aucun job." }
            }
            val branch = a.str("branch")?.let { "&branch=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            (get("/repos/${r.full}/actions/runs?per_page=${a.int("max") ?: 15}$branch") as JsonObject).a("workflow_runs").joinToString("\n") { w ->
                "${w.s("id")} ${w.s("name")} sur ${w.s("head_branch")} (${w.s("head_sha")?.take(7)}) : ${w.s("status")} ${w.s("conclusion") ?: ""} — ${w.s("created_at")?.take(16)}"
            }.ifEmpty { "Aucune exécution." }
        },
        tool("github.releases", "GitHub : versions publiées",
            "Versions publiées (Releases) d'un dépôt GitHub, avec leurs fichiers téléchargeables.",
            S.obj(repoArg, maxArg, required = listOf("repo")), listOf("release", "version", "apk", "télécharger")) { a ->
            val r = repoOf(a)
            get("/repos/${r.full}/releases?per_page=${a.int("max") ?: 10}").objects().joinToString("\n\n") { rel ->
                "${rel.s("tag_name")} « ${rel.s("name")} »${if (rel.s("prerelease") == "true") " (pré-version)" else ""} — ${rel.s("published_at")?.take(10)}\n" +
                    rel.a("assets").joinToString("\n") { f -> "  ${f.s("name")} (${f.s("size")} o) ${f.s("browser_download_url")}" }
            }.ifEmpty { "Aucune version publiée." }
        },
        tool("github.issue.create", "GitHub : créer une issue",
            "Crée une issue sur un dépôt GitHub (visible publiquement si le dépôt est public). Confirmation du propriétaire ; jeton github.com requis.",
            S.obj(repoArg, "title" to S.str("Titre"), "body" to S.str("Description (Markdown)"), required = listOf("repo", "title")),
            listOf("créer une issue", "signaler un bug"), write = true) { a ->
            val r = repoOf(a)
            val o = send("POST", "/repos/${r.full}/issues", buildJsonObject { put("title", a.str("title").orEmpty()); a.str("body")?.let { put("body", it) } })
            "Issue #${o.s("number")} créée : ${o.s("html_url")}"
        },
        tool("github.comment", "GitHub : commenter",
            "Ajoute un commentaire à une issue ou une pull request GitHub (number). Confirmation du propriétaire ; jeton github.com requis.",
            S.obj(repoArg, "number" to S.int("Numéro de l'issue ou de la PR", 1, null), "body" to S.str("Commentaire (Markdown)"), required = listOf("repo", "number", "body")),
            listOf("commenter", "répondre"), write = true) { a ->
            val r = repoOf(a)
            val o = send("POST", "/repos/${r.full}/issues/${a.int("number")}/comments", buildJsonObject { put("body", a.str("body").orEmpty()) })
            "Commentaire publié : ${o.s("html_url")}"
        },
        tool("github.pull.create", "GitHub : ouvrir une pull request",
            "Ouvre une pull request de la branche head vers base (branches déjà poussées). Confirmation du propriétaire ; jeton github.com requis.",
            S.obj(repoArg, "head" to S.str("Branche source (déjà poussée)"), "base" to S.str("Branche cible (ex. main)"), "title" to S.str("Titre"), "body" to S.str("Description (Markdown)"),
                required = listOf("repo", "head", "base", "title")),
            listOf("ouvrir une pr", "proposer les changements"), write = true) { a ->
            val r = repoOf(a)
            val o = send("POST", "/repos/${r.full}/pulls", buildJsonObject {
                put("head", a.str("head").orEmpty()); put("base", a.str("base").orEmpty()); put("title", a.str("title").orEmpty()); a.str("body")?.let { put("body", it) }
            })
            "Pull request #${o.s("number")} ouverte : ${o.s("html_url")}"
        },
    )
}
