package io.github.artisanguillonrenov.cortana.design

import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.effectiveUi
import io.github.artisanguillonrenov.cortana.core.tools.ToolFamilies
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.cortana.sizeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The design's pure rules: tool chips over the conversation's toolset, the screen chosen, sizes. */
class DesignLogicTest {
    @Test fun chipsFollowTheToolsetAndTheFamiliesSwitchedOff() {
        assertEquals(ToolFamilies.all.toSet(), ToolFamilies.active(Toolsets.FULL, emptyList()))
        assertEquals(emptySet<String>(), ToolFamilies.active(Toolsets.CONVERSATION, emptyList()))
        assertEquals(setOf("tools", "system", "files", "web"), ToolFamilies.active(Toolsets.ASSISTANT, emptyList()))
        assertEquals(setOf("tools", "files", "web"), ToolFamilies.active(Toolsets.ASSISTANT, listOf("system", "git")))
    }

    @Test fun switchingAChipKeepsTheToolsetWhenItCanElseTheSmallestThatOffersIt() {
        // Off in a full conversation: only that family goes.
        assertEquals(Toolsets.FULL to listOf("git"), ToolFamilies.toggle(Toolsets.FULL, emptyList(), "git", on = false))
        // On from the direct mode: the smallest toolset offering it, every other family off.
        assertEquals(Toolsets.ASSISTANT to listOf("tools", "system", "web"), ToolFamilies.toggle(Toolsets.CONVERSATION, emptyList(), "files", on = true))
        assertEquals(Toolsets.FULL to listOf("tools", "system", "files", "git", "web"), ToolFamilies.toggle(Toolsets.CONVERSATION, emptyList(), "terminal", on = true))
        // Terminal from the assistant toolset: full, and what was off (or not offered) stays off.
        assertEquals(Toolsets.FULL to listOf("system", "git"), ToolFamilies.toggle(Toolsets.ASSISTANT, listOf("system"), "terminal", on = true))
        // The last chip off: direct mode.
        assertEquals(Toolsets.CONVERSATION to emptyList<String>(), ToolFamilies.toggle(Toolsets.ASSISTANT, listOf("tools", "system", "web"), "files", on = false))
    }

    @Test fun theDesignOpensUnlessTheOwnerChoseAnotherScreen() {
        assertEquals("cortana", ChatPrefs().effectiveUi)
        assertEquals("cortana", ChatPrefs(ui = "workspace").effectiveUi) // the rc4 default, never chosen
        assertEquals("workspace", ChatPrefs(ui = "workspace", uiChosen = true).effectiveUi)
        assertEquals("classic", ChatPrefs(ui = "classic").effectiveUi)
    }

    @Test fun sizesAreWrittenTheFrenchWay() {
        assertEquals("512 o", sizeLabel(512))
        assertEquals("2 Ko", sizeLabel(2048))
        assertEquals("8,4 Mo", sizeLabel((8.4 * 1024 * 1024).toLong()))
    }
}
