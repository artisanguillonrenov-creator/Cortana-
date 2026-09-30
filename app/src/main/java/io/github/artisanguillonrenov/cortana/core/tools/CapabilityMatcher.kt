package io.github.artisanguillonrenov.cortana.core.tools

import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy

/**
 * Chooses which registered tools are offered to the model for a step (doc 04 §9): a small core,
 * the step's required capabilities, what the task already discovered, lexical matches of the
 * objective and the router's categories — capped at [max]. The session toolset (the owner's
 * boundary) is the pool; the [ToolRegistry] stays the canonical source.
 */
class CapabilityMatcher(private val discovery: ToolDiscovery) {
    data class Selection(val offered: List<ToolDefinition>, val strict: Boolean)

    fun select(
        pool: List<ToolDefinition>,
        text: String,
        categories: Set<ToolCategory>,
        required: List<String>,
        discovered: Set<String>,
        strategy: PlanStrategy,
        max: Int,
    ): Selection {
        if (pool.isEmpty()) return Selection(emptyList(), strict = true)
        val byCap = pool.associateBy { it.capability }
        val chosen = LinkedHashMap<String, ToolDefinition>()
        fun add(d: ToolDefinition?) { if (d != null) chosen.putIfAbsent(d.capability, d) }
        CORE.forEach { add(byCap[it]) }
        required.forEach { add(byCap[it]) }
        discovered.forEach { add(byCap[it]) }
        // A DAG step with declared capabilities gets exactly those (+ core + discovered): restricted toolset.
        val strict = strategy == PlanStrategy.DAG && required.isNotEmpty()
        if (strict) return Selection(chosen.values.toList(), strict = true)
        val ranked = discovery.rank(text, pool)
        ranked.filter { it.score >= ToolDiscovery.MIN_SCORE }.take(KEYWORD_HITS).forEach { if (chosen.size < max) add(it.def) }
        val score = ranked.associate { it.def.capability to it.score }
        for (cat in categories - ToolCategory.SERVICE) {
            pool.filter { it.category == cat }
                .sortedWith(compareByDescending<ToolDefinition> { score[it.capability] ?: 0.0 }.thenBy { CATEGORY_PRIORITY.indexOf(it.capability).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.capability })
                .forEach { if (chosen.size < max) add(it) }
        }
        return Selection(chosen.values.toList(), strict = false)
    }

    companion object {
        const val DISCOVER = "tools.discover"
        val CORE = listOf("ask_user", DISCOVER, "memory.search", "memory.save")
        private const val KEYWORD_HITS = 8
        /** Tools a category cannot work without, offered first when the category is selected. */
        private val CATEGORY_PRIORITY = listOf(
            "android.ui.observe", "android.ui.click", "android.ui.type", "android.ui.scroll", "android.ui.wait_for", "android.ui.submit",
            "android.nav.back", "android.app.open", "web.search", "web.fetch", "file.list", "file.read",
        )
    }
}
