package io.github.artisanguillonrenov.cortana.executors.runpod

import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.model.RunPodAddress
import io.github.artisanguillonrenov.cortana.core.model.RunPodClient
import io.github.artisanguillonrenov.cortana.core.model.RunPodResolver
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.str

/**
 * The owner's RunPod pods from Cortana (rc12): list them, and start a stopped one — a paid action,
 * always confirmed by the owner (L3), never decided by Cortana alone. Starting works with any model
 * or none: "démarre le pod" is also a direct shortcut.
 */
class RunPodTools(private val runpod: RunPodClient, private val resolver: RunPodResolver, private val settings: SettingsRepository) {

    private fun linkedNames(): Set<String> = settings.current.runpodPodNames.values.map { RunPodAddress.baseName(it).lowercase() }.toSet() +
        setOf("elyndor-5090", "cortana-code-vision")

    /** The pod a request names (or the only stopped pod Cortana uses), with an explanation when none or several fit. */
    private suspend fun pick(name: String?): Pair<RunPodClient.Pod?, String?> {
        val pods = runpod.pods()
        val wanted = name?.trim()?.trim('«', '»', '"', '\'')?.takeIf { it.isNotBlank() }
        if (wanted != null) {
            val m = pods.filter { RunPodAddress.baseName(it.name).equals(RunPodAddress.baseName(wanted), true) }
                .ifEmpty { pods.filter { it.name.contains(wanted, ignoreCase = true) } }
            return when {
                m.isEmpty() -> null to "aucun pod « $wanted » sur votre compte (pods : ${pods.joinToString { it.name }})"
                m.count { !it.running } == 1 -> m.first { !it.running } to null
                m.size == 1 -> m.single() to null
                else -> null to "plusieurs pods correspondent à « $wanted » : ${m.joinToString { "${it.name} (${it.status})" }} — précisez le nom"
            }
        }
        val mine = pods.filter { RunPodAddress.baseName(it.name).lowercase() in linkedNames() }
        val stopped = mine.filter { !it.running }
        return when {
            stopped.size == 1 -> stopped.single() to null
            stopped.isEmpty() && mine.isNotEmpty() -> mine.first() to null
            stopped.isEmpty() -> null to "aucun pod utilisé par Cortana n'a été trouvé sur votre compte"
            else -> null to "plusieurs pods sont arrêtés : ${stopped.joinToString { it.name }} — dites lequel démarrer"
        }
    }

    private fun price(p: RunPodClient.Pod) = p.costPerHr?.let { " · %.2f \$/h".format(it) }.orEmpty()

    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            capability = "runpod.pods",
            description = "Liste les pods RunPod du propriétaire : nom, état (en marche / arrêté), GPU, prix horaire, et met à jour l'adresse des fournisseurs dont le pod a migré.",
            inputSchema = S.obj(),
            baseRisk = Risk.L1, sideEffect = SideEffect.NONE, idempotency = Idempotency.INTRINSIC, dataEgress = DataEgress.EXTERNAL,
            category = ToolCategory.INTEGRATIONS, label = "Pods RunPod", destinationOf = { "RunPod" },
            tags = listOf("pod", "runpod", "gpu", "serveur", "état du pod"),
        ) { _, _ ->
            runCatching {
                val pods = runpod.pods()
                val report = resolver.refresh()
                buildString {
                    if (pods.isEmpty()) appendLine("Aucun pod sur ce compte RunPod.")
                    pods.forEach { appendLine("- ${it.name} : ${if (it.running) "en marche" else "arrêté"}${it.gpu?.let { g -> " · $g" }.orEmpty()}${price(it)}") }
                    report.changes.forEach { appendLine("Adresse mise à jour : ${it.providerName} → ${it.newUrl}") }
                    report.problems.forEach { appendLine("À vérifier : $it") }
                }.trim()
            }.fold({ ToolResult.ok(it) }, { ToolResult.error("RunPod : ${it.message}") })
        },
        ToolDefinition(
            capability = "runpod.start",
            description = "Démarre un pod RunPod arrêté du propriétaire (facturé tant qu'il tourne) puis met à jour l'adresse des fournisseurs. Sans nom : le seul pod arrêté utilisé par Cortana.",
            inputSchema = S.obj("pod" to S.str("Nom du pod (ex. elyndor-5090) ; vide = le pod arrêté utilisé par Cortana")),
            baseRisk = Risk.L3, sideEffect = SideEffect.EXTERNAL, idempotency = Idempotency.NONE, dataEgress = DataEgress.EXTERNAL,
            category = ToolCategory.INTEGRATIONS, label = "Démarrer un pod RunPod", destinationOf = { "RunPod" },
            extraTraits = setOf(Trait.FINANCIAL),
            tags = listOf("démarrer le pod", "allumer", "relancer", "réveiller", "pod", "runpod", "gpu"),
            riskClassifier = { args, _ ->
                runCatching { pick(args.str("pod")).first }.getOrNull()?.let { p ->
                    RiskAssessment(Risk.L3, listOf("dépense RunPod"), targetDescription = "Démarrer « ${p.name} »${p.gpu?.let { " ($it)" }.orEmpty()}${price(p)}, facturé tant qu'il tourne")
                }
            },
        ) { args, _ ->
            runCatching {
                val (pod, why) = pick(args.str("pod"))
                if (pod == null) return@runCatching ToolResult.error("RunPod : $why")
                if (pod.running) {
                    val r = resolver.refresh()
                    return@runCatching ToolResult.ok("Le pod « ${pod.name} » est déjà en marche.${r.changes.joinToString("") { "\nAdresse mise à jour : ${it.providerName}." }}")
                }
                val started = runpod.start(pod.id)
                val r = resolver.refresh()
                ToolResult.ok("Pod « ${started.name.ifBlank { pod.name }} » démarré${price(started)}. Le modèle sera prêt dans une à quelques minutes, le temps de se charger." +
                    r.changes.joinToString("") { "\nAdresse mise à jour : ${it.providerName}." })
            }.getOrElse { ToolResult.error("RunPod : ${it.message}") }
        },
    )
}
