package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.contracts.SpecialistProfile
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition

/**
 * Specialists (doc 04 §17): ephemeral cognitive workers the one orchestrator runs for a plan step —
 * minimal context, limited tools, own budgets, a [SpecialistResult] back. They never own a loop,
 * never transition the task, never read memory or the conversation.
 */
class SpecialistRegistry {
    class Spec(val profile: SpecialistProfile, val readOnly: Boolean, val instructions: String, val select: (ToolDefinition) -> Boolean)

    private val none: (ToolDefinition) -> Boolean = { it.sideEffect == SideEffect.NONE }
    private val publishing = setOf("repo.push", "repo.merge", "repo.pull", "repo.clone", "code.delete", "artifact.export", "workspace.create")
    private val verifying = setOf("build.run", "test.run", "lint.run")

    private val specs: Map<String, Spec> = listOf(
        Spec(SpecialistProfile("researcher", "Chercheur", "Recherche et recoupe des sources Web", emptyList(), maxToolCalls = 10, maxModelCalls = 8, maxMinutes = 6, outputSchemaHint = "faits sourcés"),
            true, "Tu es le chercheur de Cortana. Cherche, lis et recoupe ; cite tes sources ; n'agis sur aucun site.") { it.category == ToolCategory.WEB && none(it) },
        Spec(SpecialistProfile("code_analyst", "Analyste de code", "Lit le projet : structure, symboles, impact, risques", emptyList(), maxToolCalls = 12, maxModelCalls = 8, maxMinutes = 6, outputSchemaHint = "fichiers et symboles concernés, risques, tests à lancer"),
            true, "Tu es l'analyste de code de Cortana. Tu lis sans rien modifier et tu rends un diagnostic précis : fichiers et symboles concernés, impact, risques, tests à lancer.") { it.category == ToolCategory.DEV && none(it) && it.capability != "repo.fetch" },
        Spec(SpecialistProfile("implementer", "Implémenteur", "Applique les modifications et lance les vérifications", emptyList(), maxToolCalls = 16, maxModelCalls = 10, maxMinutes = 10, outputSchemaHint = "modifications faites, résultats des tests"),
            false, "Tu es l'implémenteur de Cortana. Applique les modifications demandées par petits patchs vérifiables, puis lance les tests. Tu ne publies rien (ni push, ni fusion).") { it.category == ToolCategory.DEV && it.capability !in publishing },
        Spec(SpecialistProfile("reviewer", "Relecteur", "Relit les modifications et vérifie les tests", emptyList(), maxToolCalls = 10, maxModelCalls = 8, maxMinutes = 8, outputSchemaHint = "verdict, points bloquants, points mineurs"),
            false, "Tu es le relecteur de Cortana. Relis les modifications (review_changes), vérifie les tests et rends un verdict argumenté. Tu ne modifies rien.") { it.category == ToolCategory.DEV && (none(it) || it.capability in verifying) && it.capability != "repo.fetch" },
        Spec(SpecialistProfile("test_analyst", "Analyste de tests", "Lance et interprète les tests", emptyList(), maxToolCalls = 10, maxModelCalls = 6, maxMinutes = 8, outputSchemaHint = "tests en échec, cause probable"),
            false, "Tu es l'analyste de tests de Cortana. Lance les tests, lis les échecs et donne leur cause probable. Tu ne modifies rien.") { it.category == ToolCategory.DEV && (none(it) || it.capability in verifying) && it.capability != "repo.fetch" },
        Spec(SpecialistProfile("security_reviewer", "Relecteur sécurité", "Cherche secrets, injections, permissions excessives", emptyList(), maxToolCalls = 10, maxModelCalls = 6, maxMinutes = 6, outputSchemaHint = "risques classés par gravité"),
            true, "Tu es le relecteur sécurité de Cortana : secrets, entrées non validées, injections, permissions excessives. Tu ne modifies rien.") { it.category == ToolCategory.DEV && none(it) && it.capability != "repo.fetch" },
        Spec(SpecialistProfile("document_analyst", "Analyste de documents", "Lit et résume des documents", emptyList(), maxToolCalls = 10, maxModelCalls = 6, maxMinutes = 6, outputSchemaHint = "points clés avec références"),
            true, "Tu es l'analyste de documents de Cortana. Lis, extrais et résume avec des références précises. Tu ne modifies rien.") { (it.category == ToolCategory.FILES || it.category == ToolCategory.DOCUMENTS) && none(it) },
        Spec(SpecialistProfile("planner_specialist", "Planificateur", "Découpe un problème en étapes", emptyList(), maxToolCalls = 0, maxModelCalls = 3, maxMinutes = 3, outputSchemaHint = "étapes ordonnées"),
            true, "Tu es le spécialiste de planification de Cortana : propose des étapes ordonnées et vérifiables, sans rien exécuter.") { false },
    ).associateBy { it.profile.profileId }

    fun get(id: String?): Spec? = id?.let { specs[it] }
    fun profiles(): List<SpecialistProfile> = specs.values.map { it.profile }
    fun isReadOnly(id: String?) = get(id)?.readOnly == true
    fun describe(): String = specs.values.joinToString("\n") { "- ${it.profile.profileId} : ${it.profile.description}${if (it.readOnly) " (lecture seule)" else ""}" }
}
