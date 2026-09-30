package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline
import io.github.artisanguillonrenov.cortana.core.chat.MessageMeta
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.memory.ChatDraftEntity
import io.github.artisanguillonrenov.cortana.core.memory.ChatPinEntity
import io.github.artisanguillonrenov.cortana.core.memory.ChatQueueEntity
import io.github.artisanguillonrenov.cortana.core.memory.ContextCheckpointEntity
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** H1/H5/H6 store: the conversation tree, branch switching, legacy healing and crash-safe streaming snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatTreeTest : CortanaTestBase() {
    private val repo get() = c.conversations
    private fun meta(m: MessageMeta) = AppJson.encodeToString(MessageMeta.serializer(), m)
    private fun ids(l: List<MessageEntity>) = l.map { it.text }

    @Test fun h1LinearConversationKeepsItsOrderAndEveryMessageExtendsTheLeaf() = runBlocking {
        val s = repo.createSession()
        val texts = listOf("q1", "r1", "q2", "r2", "q3")
        val added = texts.mapIndexed { i, t -> repo.addMessage(s.id, if (i % 2 == 0) Roles.USER else Roles.ASSISTANT, t) }
        assertEquals(texts, ids(repo.messages(s.id)))
        assertEquals(texts, ids(repo.allMessages(s.id)))
        assertEquals(listOf(null) + added.dropLast(1).map { it.id }, added.map { it.parentId })
        assertEquals(added.last().id, repo.session(s.id)!!.activeLeafId)
        assertEquals(texts, ids(repo.observePath(s.id).first()))
        // the window keeps the newest messages
        assertEquals(listOf("q2", "r2", "q3"), ids(repo.path(s.id, limit = 2)))
    }

    @Test fun h5EditRegenerateAndSwitchKeepEveryBranch() = runBlocking {
        val s = repo.createSession()
        val q1 = repo.addMessage(s.id, Roles.USER, "q1")
        val r1 = repo.addMessage(s.id, Roles.ASSISTANT, "r1")
        val q2 = repo.addMessage(s.id, Roles.USER, "q2")
        val r2 = repo.addMessage(s.id, Roles.ASSISTANT, "r2")

        // edit q2: a sibling under r1, the old version stays
        repo.setLeaf(s.id, r1.id)
        val q2b = repo.addMessage(s.id, Roles.USER, "q2 corrigée", metaJson = meta(MessageMeta(editedFrom = q2.id, kind = "edit")))
        val r2b = repo.addMessage(s.id, Roles.ASSISTANT, "r2 bis")
        assertEquals(listOf("q1", "r1", "q2 corrigée", "r2 bis"), ids(repo.messages(s.id)))
        assertEquals(6, repo.allMessages(s.id).size)
        assertEquals(listOf(q2.id, q2b.id), repo.childrenOf(listOf(r1.id)).map { it.id })

        // regenerate r2b: a sibling under q2b
        repo.setLeaf(s.id, q2b.id)
        val r2c = repo.addMessage(s.id, Roles.ASSISTANT, "r2 ter", metaJson = meta(MessageMeta(kind = "regenerate")))
        assertEquals(listOf("q1", "r1", "q2 corrigée", "r2 ter"), ids(repo.messages(s.id)))

        // the timeline shows "Version 2/2" on the user turn and "Réponse 2/2" on the answer
        val path = repo.messages(s.id)
        val items = ChatTimeline.build(path, repo.branchMap(s.id, path))
        val user = items.filterIsInstance<TimelineItem.User>().last()
        assertEquals("2/2", user.versions!!.label); assertEquals(listOf(q2.id, q2b.id), user.versions!!.ids)
        val answer = items.filterIsInstance<TimelineItem.Assistant>().last()
        assertEquals(listOf(r2b.id, r2c.id), answer.variants!!.ids); assertEquals(1, answer.variants!!.index)
        assertNull("single answer, no navigator", items.filterIsInstance<TimelineItem.Assistant>().first().variants)

        // switching back to the first version follows its newest leaf
        repo.setLeaf(s.id, repo.newestLeafUnder(q2.id))
        assertEquals(listOf("q1", "r1", "q2", "r2"), ids(repo.messages(s.id)))
        assertEquals(r2.id, repo.session(s.id)!!.activeLeafId)
        repo.setLeaf(s.id, repo.newestLeafUnder(q2b.id))
        assertEquals(r2c.id, repo.session(s.id)!!.activeLeafId)
        // a message of another conversation can never become the leaf
        val other = repo.createSession()
        val foreign = repo.addMessage(other.id, Roles.USER, "ailleurs")
        assertTrue(runCatching { repo.setLeaf(s.id, foreign.id) }.isFailure)
        assertEquals(q1.id, repo.messages(s.id).first().id)
    }

    @Test fun h1LegacyUnlinkedRowsAreHealedIntoOneBranch() = runBlocking {
        val s = repo.createSession()
        // rows written without parents (restored from an older backup / imported), same millisecond for two of them
        listOf("a" to 10L, "b" to 20L, "c" to 20L, "d" to 30L).forEachIndexed { i, (t, at) ->
            c.db.messages().insert(MessageEntity("legacy$i", s.id, if (i % 2 == 0) Roles.USER else Roles.ASSISTANT, t, at))
        }
        assertNull(repo.session(s.id)!!.activeLeafId)
        assertEquals(listOf("a", "b", "c", "d"), ids(repo.messages(s.id)))
        assertEquals("legacy3", repo.session(s.id)!!.activeLeafId)
        assertEquals(listOf(null, "legacy0", "legacy1", "legacy2"), repo.allMessages(s.id).map { it.parentId })
        // a dangling pointer (message deleted) falls back to the newest message
        repo.updateSession(repo.session(s.id)!!.copy(activeLeafId = "gone"))
        assertEquals("d", repo.messages(s.id).last().text)
        // the next message continues the healed branch
        assertEquals("legacy3", repo.addMessage(s.id, Roles.USER, "e").parentId)
    }

    @Test fun h6StreamingSnapshotIsFinalisedInPlaceAndDanglingStreamsAreKept() = runBlocking {
        val s = repo.createSession()
        val q = repo.addMessage(s.id, Roles.USER, "question")
        repo.streamSnapshot(s.id, "live1", "Bon", runId = "run1", taskId = "t1")
        var row = repo.message("live1")!!
        assertEquals(MessageStatus.STREAMING, row.status); assertEquals(q.id, row.parentId); assertEquals("run1", row.runId)
        assertEquals("live1", repo.session(s.id)!!.activeLeafId)
        repo.streamSnapshot(s.id, "live1", "Bonjour, voici", runId = "run1", taskId = "t1")
        assertEquals("Bonjour, voici", repo.message("live1")!!.text)
        // the final answer reuses the row: same place in the tree, no duplicate
        repo.addMessage(s.id, Roles.ASSISTANT, "Bonjour, voici la réponse.", taskId = "t1", id = "live1")
        row = repo.message("live1")!!
        assertEquals(MessageStatus.COMPLETE, row.status); assertEquals(q.id, row.parentId); assertEquals("Bonjour, voici la réponse.", row.text)
        assertEquals(2, repo.allMessages(s.id).size)
        // a late snapshot never overwrites a finished answer
        repo.streamSnapshot(s.id, "live1", "Bonj", runId = "run1", taskId = "t1")
        assertEquals("Bonjour, voici la réponse.", repo.message("live1")!!.text)

        // STOP keeps the partial text
        repo.addMessage(s.id, Roles.USER, "autre")
        repo.streamSnapshot(s.id, "live2", "Début de", runId = "run2", taskId = "t2")
        repo.endStream("live2", MessageStatus.STOPPED)
        assertEquals(MessageStatus.STOPPED to "Début de", repo.message("live2")!!.let { it.status to it.text })
        // process death: the streaming row is kept and marked interrupted at the next start
        repo.addMessage(s.id, Roles.USER, "encore")
        repo.streamSnapshot(s.id, "live3", "Partiel", runId = "run3", taskId = "t3")
        assertEquals(1, repo.interruptDanglingStreams())
        assertEquals(MessageStatus.INTERRUPTED, repo.message("live3")!!.status)
        assertEquals(0, repo.interruptDanglingStreams())

        val items = ChatTimeline.build(repo.messages(s.id))
        val last = items.filterIsInstance<TimelineItem.Assistant>().last()
        assertEquals("Partiel", last.text); assertTrue(last.canContinue)
        assertTrue(last.parts.any { it is MessagePart.SystemEvent && it.kind == "interrupted" })
    }

    @Test fun h1DeletingAConversationRemovesItsWorkspaceState() = runBlocking {
        val s = repo.createSession()
        repo.addMessage(s.id, Roles.USER, "x")
        val chat = c.db.chat()
        chat.upsertDraft(ChatDraftEntity(s.id, "brouillon", updatedAt = 1))
        chat.enqueue(ChatQueueEntity("q1", s.id, "plus tard", position = 1, createdAt = 1))
        chat.pin(ChatPinEntity("p1", s.id, "note", "n1", "Note", "garder", 1))
        chat.addCheckpoint(ContextCheckpointEntity("k1", s.id, "résumé", null, 3, "extractive", null, 1))
        repo.deleteSession(s.id)
        assertNull(chat.draft(s.id)); assertEquals(0, chat.queue(s.id).size); assertEquals(0, chat.pins(s.id).size); assertEquals(0, chat.checkpoints(s.id).size)
        assertEquals(0, repo.allMessages(s.id).size); assertNull(repo.session(s.id))
    }
}
