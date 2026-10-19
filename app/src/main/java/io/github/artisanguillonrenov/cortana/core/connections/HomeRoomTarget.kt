package io.github.artisanguillonrenov.cortana.core.connections

import java.text.Normalizer
import java.util.Locale

/**
 * Optional fail-closed room check for Home Assistant side-effecting commands.
 *
 * Inspired by sosoj92/jarvis-assistant-vocal's room-aware satellites. Device location
 * is never guessed from IP, user voice or an LLM claim: the requested room must occur
 * as whole, consecutive tokens in the actual entity identifier/friendly name.
 *
 * Home Assistant names may not contain a room: an unmatched target is rejected, not
 * routed to a different device. Area metadata is not claimed to be available.
 */
object HomeRoomTarget {
    private fun tokens(s: String): List<String> = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

    fun matches(entity: HomeAssistantConnector.Entity, room: String): Boolean {
        val expected = tokens(room)
        if (expected.isEmpty() || room.length > 100) return false
        // Refuse one- and two-character 'rooms' which can accidentally match common suffixes.
        if (expected.all { it.length < 3 }) return false
        val available = tokens(entity.id.substringAfter('.') + " " + entity.name.orEmpty())
        return available.windowed(expected.size, 1).any { it == expected }
    }
}
