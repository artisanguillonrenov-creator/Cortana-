package io.github.artisanguillonrenov.cortana.executors.browser

import io.github.artisanguillonrenov.cortana.core.browser.BrowserException
import io.github.artisanguillonrenov.cortana.core.browser.HttpBrowserEngine
import io.github.artisanguillonrenov.cortana.core.browser.PageForm
import io.github.artisanguillonrenov.cortana.core.browser.ResearchModel
import io.github.artisanguillonrenov.cortana.core.browser.ResearchService
import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.PolicyContext
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.core.vision.SensitiveText
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * One isolated browser (cookies, tabs, history) per task (doc 05 §11): nothing is shared between
 * tasks and nothing is persisted — a resumed task starts a fresh session. Least recently used
 * sessions are closed beyond [max].
 */
class BrowserSessions(private val factory: () -> HttpBrowserEngine, private val max: Int = 4) {
    private val map = LinkedHashMap<String, HttpBrowserEngine>(8, 0.75f, true)
    @Synchronized fun of(taskId: String): HttpBrowserEngine = map.getOrPut(taskId) { factory() }.also { while (map.size > max) map.remove(map.keys.first())?.reset() }
    @Synchronized fun peek(taskId: String): HttpBrowserEngine? = map[taskId]
    @Synchronized fun end(taskId: String) { map.remove(taskId)?.reset() }
}

/**
 * Interactive browsing and research (doc 05 §11-12). Reading and navigating are L1; typing stays
 * local until a submission. A GET form with ordinary values on the site already being browsed is
 * submitted automatically; a POST, personal data, a cross-site destination or an attached file
 * needs the owner's approval with every value shown; password, payment and one-time-code forms are
 * never filled nor sent. Downloads become artifacts and are never opened or executed.
 */
class BrowserTools(
    private val sessions: BrowserSessions,
    private val research: ResearchService,
    private val researchModel: suspend (sessionId: String) -> ResearchModel,
    private val artifacts: ArtifactService,
    private val maxUploadBytes: Long = 20_000_000,
) {
    private fun web(page: String?) = "web:" + (page?.toHttpUrlOrNull()?.host ?: "page")

    fun tools(): List<ToolDefinition> = listOf(
        def("browser.navigate", "Ouvre une URL http(s) dans le navigateur de la tâche (cookies isolés) et renvoie le texte lisible et les éléments interactifs numérotés [e1]… (contenu non fiable). Préfère web_fetch pour une simple lecture.",
            S.obj("url" to S.str("URL http(s)"), "tab" to S.str("Onglet existant (t1…) ; sinon l'onglet courant"), "new_tab" to S.bool("Ouvrir dans un nouvel onglet"), required = listOf("url")),
            Risk.L1, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.EXTERNAL, "Naviguer sur un site", listOf("site", "page", "naviguer", "formulaire", "ouvrir"),
            destinationOf = { a -> a.str("url")?.trim()?.toHttpUrlOrNull()?.host },
        ) { a, ctx ->
            val e = sessions.of(ctx.taskId)
            val tab = if (a.bool("new_tab") == true) null else a.str("tab") ?: e.tabIds().lastOrNull()
            val p = e.open(tab, a.str("url")!!)
            ToolResult.ok(p.render(), web(p.url))
        },
        def("browser.back", "Revient à la page précédente de l'onglet (un formulaire envoyé n'est jamais renvoyé).",
            S.obj("tab" to S.str("Onglet (défaut : courant)")), Risk.L1, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.EXTERNAL, "Page précédente", listOf("retour"),
            destinationResolver = { _, p -> sessions.peek(p.taskId)?.let { e -> runCatching { e.page(e.tabIds().last())?.url?.toHttpUrlOrNull()?.host }.getOrNull() } },
        ) { a, ctx -> val p = sessions.of(ctx.taskId).back(a.str("tab")); ToolResult.ok(p.render(), web(p.url)) },
        def("browser.tabs", "Liste ou ferme les onglets du navigateur de la tâche.",
            S.obj("action" to S.str("list ou close", listOf("list", "close")), "tab" to S.str("Onglet à fermer"), required = listOf("action")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, "Onglets du navigateur", listOf("onglet"),
        ) { a, ctx ->
            val e = sessions.of(ctx.taskId)
            if (a.str("action") == "close") { e.close(a.str("tab") ?: throw BrowserException("Onglet requis")); }
            val ids = e.tabIds()
            ToolResult.ok(if (ids.isEmpty()) "Aucun onglet ouvert." else ids.joinToString("\n") { id -> e.page(id).let { p -> "$id : ${p?.title ?: "(vide)"} ${p?.url.orEmpty()}" } })
        },
        def("browser.extract", "Relit la page courante (texte, éléments, formulaires). save=true l'enregistre comme artefact texte avec sa provenance.",
            S.obj("tab" to S.str("Onglet (défaut : courant)"), "max_chars" to S.int("Taille du texte (défaut 6000)", 500, 30000), "save" to S.bool("Enregistrer comme artefact")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, "Lire la page", listOf("extraire", "texte", "exporter"),
            classifier = { a, _ -> if (a.bool("save") == true) RiskAssessment(Risk.L1, listOf("Enregistre un artefact")) else null },
        ) { a, ctx ->
            val e = sessions.of(ctx.taskId)
            val tab = a.str("tab") ?: e.tabIds().lastOrNull() ?: throw BrowserException("Aucun onglet ouvert : utilise browser_navigate.")
            val p = e.page(tab) ?: throw BrowserException("Onglet vide")
            val out = p.render(a.int("max_chars") ?: 6_000)
            if (a.bool("save") == true) {
                val doc = "Source : ${p.url}\nTitre : ${p.title.orEmpty()}\nConsultée : ${java.time.Instant.ofEpochMilli(p.fetchedAt)}\nEmpreinte sha256 : ${p.sha256}\n\n${p.text}"
                val art = artifacts.registerText(doc, "page", (p.title ?: p.url.toHttpUrlOrNull()?.host ?: "page").take(60) + ".txt", ctx.taskId, "browser.extract",
                    mapOf("url" to p.url, "sha256" to p.sha256, "fetchedAt" to p.fetchedAt.toString()))
                return@def ToolResult.ok("$out\n\nPage enregistrée : artefact ${art.artifactId}", web(p.url))
            }
            ToolResult.ok(out, web(p.url))
        },
        def("browser.type", "Saisit un texte dans un champ [eN], choisit une option de liste ou coche/décoche une case (value true/false). Rien n'est envoyé avant browser_click sur le bouton du formulaire.",
            S.obj("ref" to S.str("Élément [eN]"), "value" to S.str("Texte, option ou true/false"), "tab" to S.str("Onglet (défaut : courant)"), required = listOf("ref", "value")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, "Remplir un champ", listOf("saisir", "remplir", "champ", "cocher", "choisir"),
            classifier = { a, p -> typeRisk(a, p) },
        ) { a, ctx ->
            typeRisk(a, null)?.takeIf { it.deny }?.let { return@def ToolResult.error(it.denyReason!!) }
            ToolResult.ok(sessions.of(ctx.taskId).fill(a.str("tab"), a.str("ref")!!, a.str("value")!!))
        },
        def("browser.click", "Clique : un lien [eN] ouvre sa page, un bouton d'envoi [eN] (ou un formulaire [fN]) envoie le formulaire, une case se coche. Les envois sensibles demandent l'accord du propriétaire.",
            S.obj("ref" to S.str("Élément [eN] ou formulaire [fN]"), "tab" to S.str("Onglet (défaut : courant)"), required = listOf("ref")),
            Risk.L1, SideEffect.EXTERNAL, Idempotency.NONE, DataEgress.EXTERNAL, "Cliquer / envoyer un formulaire", listOf("cliquer", "envoyer", "valider", "rechercher", "lien", "bouton"),
            classifier = { a, p -> clickRisk(a, p.taskId, p.tainted) },
            destinationResolver = { a, p -> clickDestination(a, p.taskId) },
        ) { a, ctx ->
            // The live page must still be what the owner (or the policy) approved.
            val live = clickRisk(a, ctx.taskId, ctx.tainted)
            if (live != null && (live.deny || live.risk.level > ctx.approvedRisk.level)) {
                return@def ToolResult.error(live.denyReason ?: "La page ou le formulaire a changé depuis l'autorisation : relis la page et redemande.")
            }
            val p = sessions.of(ctx.taskId).click(a.str("tab"), a.str("ref")!!)
            ToolResult.ok(p.render(), web(p.url))
        },
        def("browser.wait", "Attend qu'un texte apparaisse sur la page courante (actualisée toutes les 3 s, uniquement si elle a été obtenue sans envoi de formulaire).",
            S.obj("text" to S.str("Texte attendu"), "timeout_s" to S.int("Délai maximal en secondes (défaut 30)", 3, 120), "tab" to S.str("Onglet (défaut : courant)"), required = listOf("text")),
            Risk.L1, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.EXTERNAL, "Attendre sur une page", listOf("attendre", "actualiser"), timeoutMs = 130_000,
            destinationResolver = { a, p -> currentHost(p.taskId, a.str("tab")) },
        ) { a, ctx ->
            val e = sessions.of(ctx.taskId)
            val p = e.waitFor(a.str("tab"), a.str("text")!!, (a.int("timeout_s") ?: 30) * 1_000L)
                ?: return@def ToolResult.error("« ${a.str("text")} » n'est pas apparu dans le délai.")
            ToolResult.ok(p.render(), web(p.url))
        },
        def("browser.download", "Télécharge un lien [eN] ou une URL comme artefact (jamais ouvert ni exécuté automatiquement).",
            S.obj("target" to S.str("Élément [eN] ou URL"), "tab" to S.str("Onglet (défaut : courant)"), required = listOf("target")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.EXTERNAL, "Télécharger un fichier", listOf("telecharger", "fichier", "pdf", "document"), timeoutMs = 120_000,
            classifier = { a, p -> downloadTarget(a, p.taskId)?.let { u -> if (HttpBrowserEngine.DANGEROUS.containsMatchIn(u.substringBefore('?'))) RiskAssessment(Risk.L2, listOf("Fichier exécutable ou installable"), targetDescription = u) else RiskAssessment(Risk.L1, targetDescription = u) } },
            destinationResolver = { a, p -> downloadTarget(a, p.taskId)?.toHttpUrlOrNull()?.host },
        ) { a, ctx ->
            val d = sessions.of(ctx.taskId).download(a.str("tab"), a.str("target")!!)
            val tmp = File.createTempFile("cortana-download", ".part")
            try {
                tmp.writeBytes(d.bytes)
                val art = artifacts.register(tmp, "download", d.name, ctx.taskId, "browser.download", mapOf("url" to d.url, "contentType" to d.contentType, "dangerous" to d.dangerous.toString()), listOf(d.url))
                ToolResult.ok("Fichier « ${art.name} » (${d.bytes.size} octets, ${d.contentType.ifEmpty { "type inconnu" }}) enregistré : artefact ${art.artifactId}, sha256 ${art.sha256.take(16)}…" +
                    if (d.dangerous) "\n⚠️ Fichier exécutable/installable : il ne sera jamais ouvert ni installé automatiquement." else "", web(d.url))
            } finally { tmp.delete() }
        },
        def("browser.upload", "Joint un artefact (fichier de Cortana) à un champ fichier [eN]. L'envoi n'a lieu qu'au clic sur le bouton du formulaire, après accord du propriétaire.",
            S.obj("ref" to S.str("Champ fichier [eN]"), "artifact_id" to S.str("Identifiant de l'artefact à joindre"), "tab" to S.str("Onglet (défaut : courant)"), required = listOf("ref", "artifact_id")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, "Joindre un fichier", listOf("joindre", "televerser", "envoyer un fichier", "piece jointe"),
        ) { a, ctx ->
            val art = artifacts.get(a.str("artifact_id")!!) ?: return@def ToolResult.error("Artefact introuvable")
            val f = artifacts.file(art)
            if (!f.isFile) return@def ToolResult.error("Fichier de l'artefact absent")
            if (f.length() > maxUploadBytes) return@def ToolResult.error("Fichier trop volumineux (> ${maxUploadBytes / 1_000_000} Mo)")
            sessions.of(ctx.taskId).attach(a.str("tab"), a.str("ref")!!, f)
            ToolResult.ok("« ${art.name} » (${f.length()} octets) joint à [${a.str("ref")}] ; il partira au clic sur le bouton d'envoi, après accord.")
        },
        def("research.run", "Recherche approfondie multi-sources : requêtes, classement de fiabilité, lecture des sources, faits avec citations vérifiées, divergences, synthèse citée [n]. Produit un rapport (artefact).",
            S.obj("question" to S.str("Question à instruire"), "max_sources" to S.int("Sources à lire (défaut 5)", 2, 8), required = listOf("question")),
            Risk.L1, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.EXTERNAL, "Recherche approfondie", listOf("recherche", "sources", "comparer", "synthese", "enquete", "rapport", "citer"),
            timeoutMs = 300_000, maxOutputBytes = 20_000, destinationOf = { "recherche web" },
        ) { a, ctx ->
            val question = a.str("question")!!
            val report = research.run(question, researchModel(ctx.sessionId), maxSources = a.int("max_sources") ?: 5)
            val art = artifacts.registerText(report.markdown(), "research", "recherche-" + question.take(40).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-') + ".md",
                ctx.taskId, "research.run", mapOf("sources" to report.used.size.toString()))
            val used = report.used
            ToolResult.ok(buildString {
                append(report.summary.ifBlank { "Aucun fait vérifiable trouvé." }).append("\n\nSources :\n")
                used.forEach { append("[${it.n}] ${it.title ?: it.host} — ${it.url}\n") }
                if (report.conflicts.isNotEmpty()) append("\nDivergences : ${report.conflicts.size} (détail dans le rapport).\n")
                append("\nRapport complet : artefact ${art.artifactId} (${report.facts.size} fait(s) cité(s), ${report.rejectedFacts} écarté(s)).")
            }, "web:research")
        },
    )

    // ---- policy helpers (evaluated at approval time and re-checked at execution)

    private fun currentHost(taskId: String, tab: String?): String? = sessions.peek(taskId)?.let { e ->
        (tab ?: e.tabIds().lastOrNull())?.let { e.page(it)?.url?.toHttpUrlOrNull()?.host }
    }

    private fun typeRisk(a: JsonObject, p: PolicyContext?): RiskAssessment? {
        val value = a.str("value").orEmpty()
        if (SensitiveText.isSensitive(value)) return RiskAssessment(Risk.L3, deny = true, denyReason = "Numéro de carte, IBAN ou code à usage unique : jamais saisi par Cortana.")
        val e = p?.let { sessions.peek(it.taskId) } ?: return null
        val el = runCatching { e.element(a.str("tab"), a.str("ref").orEmpty()) }.getOrNull() ?: return null
        if (el.sensitive) return RiskAssessment(Risk.L3, deny = true, denyReason = "Champ sensible (« ${el.label} » : mot de passe, carte, code) : le propriétaire le remplit lui-même.")
        return null
    }

    private fun clickDestination(a: JsonObject, taskId: String): String? {
        val e = sessions.peek(taskId) ?: return null
        val tab = a.str("tab"); val ref = a.str("ref").orEmpty()
        val form = runCatching { e.formOf(tab, ref) }.getOrNull()
        val el = runCatching { e.element(tab, ref) }.getOrNull()
        return when {
            el?.kind == "link" -> el.href?.toHttpUrlOrNull()?.host
            form != null && (el == null || el.kind == "submit") -> form.action.toHttpUrlOrNull()?.host
            else -> currentHost(taskId, tab)
        }
    }

    private fun downloadTarget(a: JsonObject, taskId: String): String? {
        val t = a.str("target") ?: return null
        if (!Regex("^e\\d+$").matches(t)) return t
        return sessions.peek(taskId)?.let { e -> runCatching { e.element(a.str("tab"), t).href }.getOrNull() }
    }

    private val email = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")
    private val phone = Regex("(?:\\+\\d|\\b0\\d)(?:[ .-]?\\d){7,}")
    private fun personalValue(v: String) = email.containsMatchIn(v) || phone.containsMatchIn(v) || SensitiveText.isSensitive(v)

    private fun clickRisk(a: JsonObject, taskId: String, tainted: Boolean): RiskAssessment? {
        val e = sessions.peek(taskId) ?: return null
        val tab = a.str("tab"); val ref = a.str("ref").orEmpty()
        val el = runCatching { e.element(tab, ref) }.getOrNull()
        if (el?.kind == "link") return RiskAssessment(Risk.L1, targetDescription = "Lien « ${el.label} » → ${el.href}")
        if (el != null && el.kind != "submit") return null
        val form = runCatching { e.formOf(tab, ref) }.getOrNull() ?: return null
        return submissionRisk(e, tab, form, tainted)
    }

    private fun submissionRisk(e: HttpBrowserEngine, tab: String?, form: PageForm, tainted: Boolean): RiskAssessment {
        if (form.sensitive) return RiskAssessment(Risk.L3, deny = true, denyReason = "Formulaire sensible (mot de passe, paiement ou code) : jamais envoyé par Cortana — le propriétaire le remplit lui-même dans Chrome.")
        val values = e.preview(tab, form.ref)
        val files = e.attachments(tab, form.ref)
        val pageHost = (tab ?: e.tabIds().lastOrNull())?.let { e.page(it)?.url?.toHttpUrlOrNull()?.host }
        val dest = form.action.toHttpUrlOrNull()?.host
        val reasons = mutableListOf<String>()
        var risk = Risk.L1
        fun raise(r: String) { risk = Risk.L2; reasons += r }
        if (form.method != "GET") raise("Envoi ${form.method} : peut créer ou modifier quelque chose sur le site")
        if (form.personal) raise("Formulaire de données personnelles")
        if (values.any { personalValue(it.second) }) raise("Des données personnelles ont été saisies")
        if (dest != null && pageHost != null && !dest.equals(pageHost, true)) raise("Envoi vers un autre site : $dest")
        if (files.isNotEmpty()) raise("Envoi de fichier(s) : ${files.joinToString { "${it.name} (${it.length()} octets)" }}")
        if (tainted && risk == Risk.L1 && values.any { it.second.length > 200 }) raise("Texte long saisi dans une tâche influencée par du contenu Web")
        val target = buildString {
            append("Formulaire « ${form.submit ?: form.ref} » → ${form.action} (${form.method})")
            if (values.isEmpty()) append("\n(aucun champ rempli)") else values.forEach { (k, v) -> append("\n• $k : ${v.take(300)}") }
        }
        return RiskAssessment(risk, reasons, targetDescription = target)
    }

    private fun def(
        cap: String, desc: String, schema: JsonObject, risk: Risk, side: SideEffect, idem: Idempotency, egress: DataEgress, label: String, tags: List<String>,
        timeoutMs: Long = 45_000, maxOutputBytes: Int = 16_000, destinationOf: ((JsonObject) -> String?)? = null,
        classifier: (suspend (JsonObject, PolicyContext) -> RiskAssessment?)? = null,
        destinationResolver: (suspend (JsonObject, PolicyContext) -> String?)? = null,
        exec: suspend (JsonObject, ToolContext) -> ToolResult,
    ) = ToolDefinition(
        cap, desc, schema, risk, side, idem, egress, ToolCategory.WEB, maxOutputBytes = maxOutputBytes, timeoutMs = timeoutMs, label = label,
        destinationOf = destinationOf, riskClassifier = classifier, tags = tags + "navigateur", destinationResolver = destinationResolver,
    ) { a, ctx ->
        try { exec(a, ctx) } catch (e: BrowserException) { ToolResult.error(e.message ?: "Erreur du navigateur") }
    }
}
