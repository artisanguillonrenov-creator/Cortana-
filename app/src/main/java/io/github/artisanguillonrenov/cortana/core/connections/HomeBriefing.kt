package io.github.artisanguillonrenov.cortana.core.connections

/**
 * Read-only, bounded home snapshot inspired by sam3gp8/jarvis-aio's proactive briefing.
 *
 * No separate scheduler, no background tracking, no outbound action: it summarizes states
 * already fetched through Cortana's authorized HomeAssistantConnector and its allowlist.
 * It can be called by voice, chat, or the existing scheduler via home.briefing.
 */
object HomeBriefing {
    data class Snapshot(
        val entitiesSeen: Int,
        val activeLights: List<String>,
        val activeSwitches: List<String>,
        val openAccess: List<String>,
        val urgent: List<String>,
        val unavailable: List<String>,
        val temperatures: List<String>,
    ) {
        fun format(): String = buildString {
            append("Bilan Home Assistant — ").append(entitiesSeen).append(" appareil(s) consulté(s).")
            fun section(label: String, values: List<String>) {
                if (values.isNotEmpty()) append("\n").append(label).append(" : ").append(values.joinToString(", "))
            }
            section("Alertes déclarées", urgent)
            section("Serrures/ouvrants ouverts", openAccess)
            section("Lumières allumées", activeLights)
            section("Prises/interrupteurs actifs", activeSwitches)
            section("Températures", temperatures)
            section("Appareils indisponibles", unavailable)
            if (listOf(activeLights, activeSwitches, openAccess, urgent, unavailable, temperatures).all { it.isEmpty() }) {
                append("\nAucun état particulier détecté dans les domaines autorisés. ")
                append("Cela ne garantit pas l'absence d'incident ni la présence de tous les capteurs.")
            }
            append("\nLecture seule ; données issues uniquement des entités autorisées.")
        }
    }

    /** Names and states are untrusted values received from Home Assistant; output is kept short. */
    fun summarize(
        entities: List<HomeAssistantConnector.Entity>,
        room: String? = null,
        maxPerSection: Int = 12,
    ): Snapshot {
        require(maxPerSection in 1..30) { "Invalid per-section limit" }
        val filter = room?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val selected = entities.asSequence().filter {
            filter == null || it.id.contains(filter, ignoreCase = true) ||
                (it.name?.contains(filter, ignoreCase = true) == true)
        }.take(1000).toList()
        fun safe(e: HomeAssistantConnector.Entity): String {
            val name = e.name?.take(80)?.takeIf { it.isNotBlank() } ?: e.id
            return name.replace(Regex("[\\r\\n\\t]+"), " ").take(90)
        }
        fun values(predicate: (String, String) -> Boolean): List<String> =
            selected.asSequence().filter { e ->
                val domain = e.id.substringBefore('.')
                predicate(domain, e.state.lowercase())
            }.map(::safe).take(maxPerSection).toList()
        val unavailable = values { _, state -> state in setOf("unavailable", "unknown") }
        val activeLights = values { domain, state -> domain == "light" && state == "on" }
        val activeSwitches = values { domain, state -> domain == "switch" && state == "on" }
        val openAccess = selected.asSequence().filter { e ->
            val domain = e.id.substringBefore('.')
            val state = e.state.lowercase()
            (domain == "lock" && state == "unlocked") ||
                (domain == "cover" && state in setOf("open", "opening")) ||
                (domain == "binary_sensor" && state == "on" &&
                    e.attributes["device_class"] in setOf("door", "window", "opening"))
        }.map(::safe).take(maxPerSection).toList()
        val urgent = selected.asSequence().filter { e ->
            (e.id.substringBefore('.') == "alarm_control_panel" && e.state.lowercase() == "triggered") ||
                (e.id.substringBefore('.') == "binary_sensor" && e.state.lowercase() == "on" &&
                    e.attributes["device_class"] in setOf("smoke", "carbon_monoxide", "gas"))
        }.map(::safe).take(maxPerSection).toList()
        val temperatures = selected.asSequence().filter { it.id.startsWith("climate.") }
            .filter { it.state.lowercase() !in setOf("unknown", "unavailable") }
            .mapNotNull { e ->
                val temp = e.attributes["current_temperature"]?.toDoubleOrNull()
                    ?: return@mapNotNull null
                if (!temp.isFinite() || temp !in -50.0..80.0) return@mapNotNull null
                "${safe(e)} : ${temp} °C"
            }.take(maxPerSection).toList()
        return Snapshot(selected.size, activeLights, activeSwitches, openAccess, urgent, unavailable, temperatures)
    }
}
