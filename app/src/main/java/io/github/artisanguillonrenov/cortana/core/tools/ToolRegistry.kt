package io.github.artisanguillonrenov.cortana.core.tools

import io.github.artisanguillonrenov.cortana.core.model.ToolSpec
import java.util.concurrent.ConcurrentHashMap

/** §8 — the only way the model reaches a capability. Unregistered tools cannot be called. */
class ToolRegistry {
    private val byCapability = ConcurrentHashMap<String, ToolDefinition>()
    private val byFunction = ConcurrentHashMap<String, ToolDefinition>()

    private val groups = ConcurrentHashMap<String, Set<String>>()

    @Synchronized fun register(def: ToolDefinition) {
        require(byCapability[def.capability] == null) { "duplicate capability ${def.capability}" }
        require(byFunction[def.functionName] == null) { "duplicate function name ${def.functionName}" }
        byCapability[def.capability] = def
        byFunction[def.functionName] = def
    }

    fun hasFunction(name: String) = byFunction.containsKey(name)

    /** Capabilities currently registered under a dynamic group (e.g. one MCP server). */
    fun group(group: String): Set<String> = groups[group].orEmpty()

    /**
     * Atomically replaces the tools of a dynamic group (external servers come and go). A definition
     * clashing with a tool outside the group is skipped (and reported), never overwritten.
     */
    @Synchronized fun replaceGroup(group: String, defs: List<ToolDefinition>): List<String> {
        groups.remove(group)?.forEach { cap -> byCapability.remove(cap)?.let { byFunction.remove(it.functionName) } }
        val added = LinkedHashSet<String>(); val skipped = mutableListOf<String>()
        for (d in defs) {
            if (byCapability[d.capability] != null || byFunction[d.functionName] != null) { skipped += d.capability; continue }
            byCapability[d.capability] = d; byFunction[d.functionName] = d; added += d.capability
        }
        if (added.isNotEmpty()) groups[group] = added
        return skipped
    }

    fun registerAll(defs: List<ToolDefinition>) = defs.forEach(::register)

    fun byCapability(c: String): ToolDefinition? = byCapability[c]

    /** Accepts the function name ("web_fetch") or the capability id ("web.fetch"). */
    fun resolve(name: String): ToolDefinition? = byFunction[name] ?: byCapability[name] ?: byFunction[name.replace('.', '_')]

    fun all(): List<ToolDefinition> = byCapability.values.sortedBy { it.capability }

    fun forToolset(toolset: String): List<ToolDefinition> {
        val cats = Toolsets.categories(toolset)
        return all().filter { it.category in cats }
    }

    fun specs(toolset: String): List<ToolSpec> = forToolset(toolset).map { it.spec() }
}
