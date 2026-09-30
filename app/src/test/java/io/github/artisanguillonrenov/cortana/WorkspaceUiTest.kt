package io.github.artisanguillonrenov.cortana

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key

import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.MessageMeta
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.workspace.WorkspaceScreen
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** H2–H5 on screen: phone and tablet layouts, composer, rich rendering, variants, accessibility semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceUiTest : CortanaTestBase() {
    @get:Rule val rule = createComposeRule()

    @Before fun calm() = runBlocking {
        // No indeterminate animation in tests (and the owner's "reduce motion" path is exercised).
        c.settings.update { it.copy(chat = it.chat.copy(reduceMotion = true)) }
        Unit
    }

    /** The screen size comes from the test's qualifiers (phone, portrait tablet, landscape tablet). */
    private fun show(s: SessionEntity?) {
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                WorkspaceScreen(openSessionId = s?.id, onOpenProviders = {}, onOpenSettings = {}, onUseClassic = {})
            }
        }
        rule.waitForIdle()
    }

    /** The timeline is built off the main thread: wait for it rather than assume it. */
    private fun waitText(text: String, ms: Long = 15_000) = rule.waitUntil(ms) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** The composer's field (the sidebar also has a text field on tablets). */
    private val composer = hasSetTextAction() and hasText("Écrivez à Cortana", substring = true)

    private fun meta(m: MessageMeta) = AppJson.encodeToString(MessageMeta.serializer(), m)

    @Config(qualifiers = "w400dp-h840dp")
    @Test fun h2PhoneKeepsTheConversationDominantAndOpensTheSidebarOnDemand() {
        val s = session(toolset = Toolsets.CONVERSATION)
        show(s)
        rule.onNodeWithText("Bonjour, je suis Cortana.").assertIsDisplayed()
        assertTrue("sidebar hidden on a phone", rule.onAllNodesWithText("Filtrer les discussions").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Discussions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Filtrer les discussions").assertExists()
    }

    @Config(qualifiers = "w1280dp-h800dp-land")
    @Test fun h2TabletLandscapeShowsSidebarConversationAndDockedPanel() {
        val s = session(toolset = Toolsets.CONVERSATION)
        show(s)
        rule.onNodeWithText("Filtrer les discussions").assertExists()
        rule.onNodeWithContentDescription("Panneau de contexte").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Panneau").assertExists()
        rule.onNodeWithText("Contexte faible").assertExists()
        // the composer stays on screen with the panel open
        rule.onNode(composer).assertExists()
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test fun h3ComposerSendsKeepsTheDraftAndShowsTheAnswer() {
        val s = session(toolset = Toolsets.CONVERSATION)
        show(s)
        rule.onNode(composer).performTextInput("Brouillon gardé")
        rule.waitUntil(8_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            runBlocking { c.db.chat().draft(s.id)?.text } == "Brouillon gardé"
        }
        server.enqueue(text("Voici **la** réponse."))
        rule.onNodeWithContentDescription("Envoyer").performClick()
        waitText("Voici la réponse.")
        // the message, and the conversation's title and preview in the sidebar
        assertTrue(rule.onAllNodesWithText("Brouillon gardé").fetchSemanticsNodes().size >= 2)
        rule.waitUntil(5_000) { !c.orchestrator.isBusy() }
        assertEquals(null, runBlocking { c.db.chat().draft(s.id) })
    }

    @Config(qualifiers = "w1000dp-h1400dp")
    @Test fun h4RichAnswerRendersCodeTableMathDiagramAndSources() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Montre-moi")
            c.conversations.addMessage(s.id, Roles.ASSISTANT,
                "## Résultat\n\n```kotlin\nval x = 1\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n$$\\frac{a}{b}$$\n\n```mermaid\ngraph TD; A-->B\n```\n\nVoir [le site](https://exemple.fr).",
                metaJson = meta(MessageMeta(modelId = "llama-3.1-8b-instruct")))
        }
        show(s)
        waitText("Résultat")
        rule.onNodeWithText("kotlin").assertExists()
        rule.onNodeWithText("Tableau · 1 ligne(s)").assertExists()
        rule.onNodeWithText("a⁄b").assertExists()
        rule.onNodeWithText("Diagramme Mermaid (source)").assertExists()
        // the model badge of the answer, besides the header's model chip
        assertTrue(rule.onAllNodesWithText("llama-3.1-8b-instruct", substring = true).fetchSemanticsNodes().size >= 2)
        rule.onNode(hasText("le site", substring = true)).assertExists()
    }

    @Config(qualifiers = "w1000dp-h1400dp")
    @Test fun h5VariantsAreNavigableAndEditingKeepsBothVersions() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            val q = c.conversations.addMessage(s.id, Roles.USER, "Question")
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Première réponse")
            c.conversations.setLeaf(s.id, q.id)
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Seconde réponse", metaJson = meta(MessageMeta(kind = "regenerate")))
        }
        show(s)
        waitText("Seconde réponse")
        rule.onNodeWithText("Réponse 2/2").assertExists()
        rule.onNodeWithText("‹").performClick()
        waitText("Première réponse")
        rule.onNodeWithText("Réponse 1/2").assertExists()
    }

    @Config(qualifiers = "w1000dp-h1400dp")
    @Test fun h5DeletingAMessageAsksFirstAndThenRemovesItAndWhatFollows() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Premier message")
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Première réponse")
            c.conversations.addMessage(s.id, Roles.USER, "Second message")
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Seconde réponse")
        }
        show(s)
        waitText("Seconde réponse")
        fun openDelete() {
            rule.onAllNodesWithContentDescription("Plus d'actions sur votre message")[1].performClick()
            rule.onNodeWithText("Supprimer ce message et la suite…").performClick()
            rule.onNodeWithText("Supprimer ce message ?").assertExists()
        }
        openDelete()
        rule.onNodeWithText("Annuler").performClick()
        rule.waitForIdle()
        assertEquals("cancel deletes nothing", 4, runBlocking { c.conversations.allMessages(s.id) }.size)
        openDelete()
        rule.onNodeWithText("Supprimer").performClick()
        rule.waitUntil(8_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText("Seconde réponse", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(listOf("Premier message", "Première réponse"), runBlocking { c.conversations.messages(s.id) }.map { it.text })
        // the answer in the conversation, and the sidebar's preview of it
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText("Première réponse", substring = true).fetchSemanticsNodes().size >= 2
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Config(qualifiers = "w1280dp-h800dp-land")
    @Test fun h11KeyboardShortcutsToggleTheSidebarAndStartANewConversation() {
        val s = session(toolset = Toolsets.CONVERSATION)
        show(s)
        rule.onNodeWithText("Filtrer les discussions").assertExists()
        rule.onNode(composer).performClick()
        rule.onNode(composer).performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.B); keyUp(Key.CtrlLeft) }
        rule.waitForIdle()
        assertTrue("Ctrl+B hides the sidebar", rule.onAllNodesWithText("Filtrer les discussions").fetchSemanticsNodes().isEmpty())
        val before = runBlocking { c.conversations.allSessions().size }
        rule.onNode(composer).performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.N); keyUp(Key.CtrlLeft) }
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            runBlocking { c.conversations.allSessions().size } == before + 1
        }
        rule.onNodeWithContentDescription("Redimensionner le panneau").assertDoesNotExist()
        rule.onNodeWithContentDescription("Panneau de contexte").performClick()
        rule.onNodeWithContentDescription("Redimensionner le panneau").assertExists()
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test fun a11yLargeTextAndHighContrastKeepEveryControl() {
        runBlocking { c.settings.update { it.copy(chat = it.chat.copy(density = "large", theme = "contrast")) } }
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking { c.conversations.addMessage(s.id, Roles.USER, "Salut"); c.conversations.addMessage(s.id, Roles.ASSISTANT, "Bonjour !") }
        show(s)
        waitText("Bonjour !")
        listOf("Envoyer", "Joindre, mode, commandes", "Discussions", "Panneau de contexte").forEach { rule.onNode(hasContentDescription(it, substring = true)).assertExists() }
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test fun a11yRolesAreHeadingsAndControlsAreLabelled() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Salut")
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Bonjour !")
        }
        show(s)
        waitText("Bonjour !")
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        rule.onNode(hasText("Vous").and(heading)).assertExists()
        rule.onNode(hasText("Cortana").and(heading)).assertExists()
        listOf("Envoyer", "Joindre, mode, commandes", "Discussions", "Menu de la discussion", "Panneau de contexte").forEach { label ->
            rule.onNode(hasContentDescription(label, substring = true)).assertExists()
        }
        rule.onNode(hasContentDescription("Dicter", substring = true)).assertExists()
    }
}
