package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.PrivacyLevel
import io.github.artisanguillonrenov.cortana.core.memory.HashingEmbedder
import io.github.artisanguillonrenov.cortana.core.memory.MemoryIndexer
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.Vectors
import io.github.artisanguillonrenov.cortana.core.model.ModelGateway
import io.github.artisanguillonrenov.cortana.core.model.ProviderHealth
import io.github.artisanguillonrenov.cortana.core.model.RouteNeed
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VNext phase 7 gates: hybrid memory (lexical + semantic, offline fallback), relations, retention, export; model routing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryAndRoutingTest : CortanaTestBase() {

    /** A fake OpenAI-compatible embedding model that knows two synonym families. */
    private fun embeddingServer(fail: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path!!.endsWith("/embeddings")) return MockResponse().setResponseCode(404)
                if (fail) return MockResponse().setResponseCode(503).setBody("""{"error":{"message":"indisponible"}}""")
                val inputs = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content.lowercase() }
                val data = inputs.mapIndexed { i, t ->
                    val v = when {
                        "voiture" in t || "automobile" in t || "clio" in t -> "[1.0,0.0,0.0]"
                        "chat" in t || "félin" in t -> "[0.0,1.0,0.0]"
                        else -> "[0.0,0.0,1.0]"
                    }
                    """{"object":"embedding","index":$i,"embedding":$v}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[${data.joinToString(",")}]}""")
            }
        }
    }

    private fun useRemoteEmbeddings(): String = runBlocking {
        val s = session()
        val route = "${s.providerId}/embed-test"
        c.settings.update { it.copy(embeddingRoute = route) }
        route
    }

    private fun save(text: String, type: String = MemoryTypes.SEMANTIC, status: String = MemoryStatus.ACTIVE) =
        runBlocking { c.memory.save(text, type, status, "explicit").memory }

    @Test fun localEmbedderCapturesInflectionsOffline() {
        val e = HashingEmbedder()
        val a = e.vector("Je préfère le café noir le matin")
        val close = Vectors.cosine(a, e.vector("préférences de cafés"))
        val far = Vectors.cosine(a, e.vector("La voiture est garée devant la maison"))
        assertTrue("close=$close far=$far", close > far + 0.15)
        assertEquals(1.0, Vectors.cosine(a, Vectors.fromBytes(Vectors.toBytes(a))), 1e-6)
    }

    @Test fun semanticRetrievalFindsWhatLexicalSearchCannot() {
        embeddingServer()
        useRemoteEmbeddings()
        val car = save("Ma voiture est une Clio rouge")
        save("J'ai un chat nommé Tigre")
        save("J'aime le jazz des années 50")
        runBlocking { c.memoryIndexer.sync() } // saves also schedule a background sync; counts are checked, not return values
        assertEquals(3, runBlocking { c.db.memories().vectors("remote:" + c.settings.current.embeddingRoute) }.size)
        assertTrue("no lexical hit", runBlocking { c.memory.search("automobile") }.isEmpty())
        val hits = runBlocking { c.memory.retrieve("mon automobile") }
        assertEquals(car.id, hits.first().memory.id)
        assertTrue(hits.first().signals["vector"]!! > 0.9)
        assertTrue(runBlocking { c.memory.retrieveForContext("parle-moi de mon automobile") }.any { it.id == car.id })
    }

    @Test fun lexicalRetrievalStillWorksWhenTheEmbeddingModelIsDown() {
        embeddingServer(fail = true)
        useRemoteEmbeddings()
        val car = save("Ma voiture est une Clio rouge")
        runBlocking { c.memoryIndexer.sync() }
        assertTrue(c.memoryIndexer.status.value.lastError != null)
        val hits = runBlocking { c.memory.retrieve("Clio") }
        assertEquals(car.id, hits.single().memory.id)
        assertEquals(0.0, hits.single().signals["vector"]!!, 0.0)
    }

    @Test fun anIndexingErrorStaysVisibleWhileARetryIsRunning() {
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS) // the retry is held in flight
                return MockResponse().setResponseCode(503).setBody("""{"error":{"message":"indisponible"}}""")
            }
        }
        useRemoteEmbeddings()
        save("Ma voiture est une Clio rouge")
        release.countDown()
        runBlocking { c.memoryIndexer.sync() }
        assertTrue(c.memoryIndexer.status.value.lastError != null)
        val retry = kotlin.concurrent.thread { runBlocking { c.memoryIndexer.sync() } }
        val gate = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { gate.await(10, java.util.concurrent.TimeUnit.SECONDS); return MockResponse().setResponseCode(503) }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!c.memoryIndexer.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("a retry pass is running", c.memoryIndexer.status.value.running)
        assertTrue("the previous error must not disappear while retrying", c.memoryIndexer.status.value.lastError != null)
        gate.countDown(); retry.join()
    }

    @Test fun changingTheEmbeddingModelReindexesEverything() = runBlocking {
        save("Aime la randonnée en montagne"); save("Travaille comme menuisier")
        c.memoryIndexer.sync()
        assertEquals(2, c.db.memories().vectors("local-hash-v1-384").size)
        assertEquals(0, c.memoryIndexer.sync()) // idempotent: nothing changed
        embeddingServer()
        val route = useRemoteEmbeddings()
        assertEquals(2, c.memoryIndexer.sync())
        assertTrue(c.db.memories().vectors("local-hash-v1-384").isEmpty())
        assertEquals(2, c.db.memories().vectors("remote:$route").size)
        val forgotten = c.memory.retrieve("randonnée").first().memory
        c.memory.forget(forgotten.id)
        c.memoryIndexer.sync()
        assertEquals(1, c.db.memories().vectors("remote:$route").size)
    }

    @Test fun relatedMemoriesComeAlongThroughTheGraph() = runBlocking {
        val home = save("Ma sœur Julie habite à Lyon")
        val treat = save("Julie adore le chocolat noir")
        c.memoryIndexer.sync()
        assertTrue(c.db.memories().edgesOf(listOf(home.id)).any { it.relation == MemoryIndexer.REL_SAME_ENTITY && it.entity == "Julie" })
        val hits = c.memory.retrieve("Où habite ma sœur ?")
        assertEquals(home.id, hits.first().memory.id)
        val related = hits.firstOrNull { it.memory.id == treat.id }
        assertTrue("graph neighbour retrieved: $hits", related != null && related.signals["graph"]!! > 0)
    }

    @Test fun entityEdgesDoNotDependOnIndexingOrder() = runBlocking {
        // "Julie" opens the second sentence, so only the first memory names it as an entity:
        // the edge must still appear when the first one was indexed before the second existed.
        val home = save("Ma sœur Julie habite à Lyon")
        c.memoryIndexer.sync()
        val treat = save("Julie adore le chocolat noir")
        c.memoryIndexer.sync()
        assertTrue(c.db.memories().edgesOf(listOf(treat.id)).any { it.fromId == home.id && it.relation == MemoryIndexer.REL_SAME_ENTITY && it.entity == "Julie" })
    }

    @Test fun retentionPurgesExpiredRecordsButKeepsFacts() = runBlocking {
        val fact = save("Est allergique aux arachides", MemoryTypes.PROFILE)
        save("Tâche « météo » terminée", MemoryTypes.EPISODIC)
        save("Semble aimer le rugby", status = MemoryStatus.PENDING)
        val gone = save("Ancien numéro de bureau")
        c.memory.forget(gone.id)
        val edited = c.memory.edit(fact.id, "Est allergique aux arachides et aux noix")!!
        c.memoryIndexer.sync()
        val purged = c.maintenance.retention(System.currentTimeMillis() + 100L * 86_400_000)
        assertEquals(3, purged) // episode (90 d), pending (30 d), deleted (30 d); superseded kept 365 d
        val left = c.memory.exportJson()
        assertTrue(left.contains(edited.text) && left.contains("Est allergique aux arachides\""))
        assertFalse(left.contains("rugby") || left.contains("météo") || left.contains("Ancien numéro"))
        assertTrue(c.db.memories().edgesOf(listOf(edited.id)).any { it.relation == MemoryIndexer.REL_SUPERSEDES })
    }

    @Test fun exportCarriesProvenanceAndEraseRemovesEverything() = runBlocking {
        save("Préfère le thé vert", MemoryTypes.PREFERENCE)
        c.memoryIndexer.sync()
        val json = AppJson.parseToJsonElement(c.memory.exportJson()).jsonObject
        assertEquals("cortana.memories", json["format"]!!.jsonPrimitive.content)
        val m = json["memories"]!!.jsonArray.single().jsonObject
        assertEquals("explicit", m["provenance"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        c.memory.eraseAll()
        assertTrue(c.db.memories().allForExport().isEmpty())
        assertTrue(c.db.memories().vectors("local-hash-v1-384").isEmpty())
    }

    @Test fun incognitoSessionsLeaveNoMemoryAtAll() {
        val base = session()
        val s = runBlocking { c.conversations.createSession(incognito = true, providerId = base.providerId, modelId = base.modelId) }
        server.enqueue(toolCall("memory_save", """{"text":"Aime le vélo","type":"preference","explicit":true}"""))
        server.enqueue(text("Je ne retiens rien en mode incognito."))
        runAndWait(s, "Retiens que j'aime le vélo")
        assertTrue(runBlocking { c.db.memories().allForExport() }.isEmpty())
        // Both the explicit-memory fast path and the model's own memory_save are refused by the executor.
        val calls = runBlocking { c.db.tasks().toolCalls(lastTask().id) }
        assertEquals(listOf("memory.save", "memory.save"), calls.map { it.capability })
        assertTrue(calls.all { it.outcome == "error" })
    }

    // ---------------------------------------------------------------- model routing

    private fun provider(name: String, url: String, preset: String = "custom", model: String = "m"): ProviderEntity = runBlocking {
        val p = c.providers.create(c.presets.byId(preset) ?: c.presets.all.first(), name, url, null)
        c.providers.update(p.copy(defaultModelId = model), null)
        c.providers.all().first { it.id == p.id }
    }

    @Test fun localProviderDetection() {
        fun p(url: String, preset: String = "custom") = ProviderEntity("x", "x", preset, url, null, null, true, 0, false, "m", createdAt = 0)
        assertTrue(ModelGateway.isLocal(p("http://192.168.1.20:11434/v1")))
        assertTrue(ModelGateway.isLocal(p("http://10.0.0.2/v1")))
        assertTrue(ModelGateway.isLocal(p("http://100.64.3.4/v1")))
        assertTrue(ModelGateway.isLocal(p("http://localhost:1234/v1")))
        assertTrue(ModelGateway.isLocal(p("http://nas.local:8080/v1")))
        assertTrue(ModelGateway.isLocal(p("https://example.com/v1", preset = "local")))
        assertFalse(ModelGateway.isLocal(p("https://api.openai.com/v1")))
        assertFalse(ModelGateway.isLocal(p("https://8.8.8.8/v1")))
    }

    @Test fun localOnlyPrivacyNeverRoutesToARemoteProvider() = runBlocking {
        val remote = provider("Distant", "https://api.example.com/v1")
        c.settings.update { it.copy(defaultProviderId = remote.id, privacyMode = ModelGateway.PRIVACY_LOCAL_ONLY) }
        assertNull(c.gateway.resolveRoute(null))
        val local = provider("Maison", server.url("/v1").toString().trimEnd('/'))
        val r = c.gateway.resolveRoute(null)!!
        assertEquals(local.id, r.providerId)
        assertTrue(r.localOnly)
        c.settings.update { it.copy(privacyMode = ModelGateway.PRIVACY_STANDARD) }
        assertEquals(remote.id, c.gateway.resolveRoute(null)!!.providerId)
        assertEquals(local.id, c.gateway.resolveRoute(null, RouteNeed(privacy = PrivacyLevel.LOCAL_ONLY))!!.providerId)
        // A local-only route refuses a remote primary outright.
        val refused = c.gateway.complete(r.copy(providerId = remote.id), listOf(), emptyList(), onDelta = {})
        assertTrue(refused.error!!.contains("Mode confidentiel"))
    }

    @Test fun codingTasksUseTheOwnersCodingModel() {
        val s = session(model = "general-model")
        runBlocking { c.settings.update { it.copy(codingRoute = "${s.providerId}/coder-model") } }
        server.enqueue(text("Voici la correction."))
        runAndWait(s, "Corrige ce bug gradle")
        assertEquals("coder-model", AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
        server.enqueue(text("Bonjour !"))
        runAndWait(s, "Bonjour")
        assertEquals("general-model", AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun circuitBreakerOpensThenProbes() {
        var now = 0L
        val h = ProviderHealth(threshold = 2, cooldownMs = 1000) { now }
        h.onFailure("p", "500", retryable = true)
        assertTrue(h.allow("p"))
        h.onFailure("p", "500", retryable = true)
        assertFalse(h.allow("p"))
        now = 1500
        assertEquals(ProviderHealth.State.HALF_OPEN, h.state("p"))
        h.onFailure("p", "500", retryable = true) // failed probe → open again
        assertFalse(h.allow("p"))
        now = 3000
        h.onSuccess("p")
        assertEquals(ProviderHealth.State.CLOSED, h.state("p"))
        h.onFailure("p", "400", retryable = false) // request errors never open the circuit
        assertTrue(h.allow("p"))
    }

    @Test fun openCircuitIsSkippedInFavourOfTheFallback() {
        val s = session(model = "main")
        val backup = runBlocking {
            val p = c.providers.create(c.presets.byId("local")!!, "Secours", server.url("/v1").toString().trimEnd('/'), null)
            c.providers.update(p.copy(defaultModelId = "backup-model", allowFallback = true, fallbackOrder = 1), null)
            p
        }
        repeat(3) { c.gateway.health.onFailure(s.providerId!!, "503", retryable = true) }
        server.enqueue(text("Réponse du secours."))
        runAndWait(s, "Bonjour")
        assertEquals("backup-model", AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(messages(s).any { it.text.contains("Secours") })
        assertTrue(backup.id.isNotEmpty())
    }
}
