package io.github.pdrakeweb.llmtiers.openai.openrouter

import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.openai.FakeOpenAiServer
import io.github.pdrakeweb.llmtiers.openai.respond
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.ServerSocket
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The recorded fixture is a trimmed copy of the real `GET /models` reply; see its `_recorded` field. */
class OpenRouterCatalogTest {
    private val server = FakeOpenAiServer()
    private val fixture = checkNotNull(javaClass.getResource("/openrouter/models.json")).readText()
    private var clock = Instant.parse("2026-11-15T12:00:00Z")

    init {
        server.onModels = { it.respond(200, fixture) }
    }

    @AfterEach
    fun stop() = server.close()

    private fun catalog(cacheFor: Duration = 1.minutes, timeout: Duration = 5.seconds, client: OkHttpClient = OkHttpClient()) =
        OpenRouterCatalog(client, server.baseUrl, cacheFor = cacheFor, timeout = timeout, now = { clock })

    private fun OpenRouterCatalog.load(refresh: Boolean = false): CatalogResult = runBlocking { withTimeout(15.seconds) { fetch(refresh) } }

    private fun models(): List<OpenRouterModel> = assertIs<CatalogResult.Ok>(catalog().load()).models

    @Test
    fun `reads every entry of the recorded reply, in order, with its fields`() {
        val result = assertIs<CatalogResult.Ok>(catalog().load())
        assertEquals(12, result.models.size)
        assertEquals(clock, result.fetchedAt)
        assertEquals("inclusionai/ling-3.2-flash:free", result.models.first().id)

        val sonnet = result.models.byId("anthropic/claude-sonnet-4.6")!!
        assertEquals("Anthropic: Claude Sonnet 4.6", sonnet.name)
        assertTrue(sonnet.description.isNotBlank())
        assertEquals(1_000_000, sonnet.contextLength)
        assertEquals(BigDecimal("0.000003"), sonnet.promptPricePerToken)
        assertEquals(BigDecimal("0.000015"), sonnet.completionPricePerToken)
        assertEquals(0, BigDecimal("3").compareTo(sonnet.promptPricePerMillionTokens), "exact, not 2.9999…")
        assertEquals("15", sonnet.completionPricePerMillionTokens!!.toPlainString())
        assertEquals(setOf("text", "image", "file"), sonnet.inputModalities)
        assertEquals(setOf("text"), sonnet.outputModalities)
        assertTrue("tools" in sonnet.supportedParameters && "structured_outputs" in sonnet.supportedParameters)
        assertEquals(Instant.ofEpochSecond(1771342990), sonnet.created)
        assertNull(sonnet.expiresOn)
        assertFalse(sonnet.isFree)
    }

    @Test
    fun `the request sends no key`() {
        catalog().load()
        val sent = server.requests.single()
        assertEquals("GET", sent.method)
        assertEquals("/v1/models", sent.path)
        assertNull(sent.headers["authorization"])
    }

    @Test
    fun `free means a free variant or both prices zero, and a router's -1 price is no price`() {
        val models = models()
        assertTrue(models.byId("inclusionai/ling-3.2-flash:free")!!.isFree)
        assertTrue(models.byId("openrouter/free")!!.isFree, "zero prices without the suffix")
        assertFalse(models.byId("meta-llama/llama-3.3-70b-instruct")!!.isFree)

        val auto = models.byId("openrouter/auto")!!
        assertNull(auto.promptPricePerToken)
        assertNull(auto.completionPricePerMillionTokens)
        assertFalse(auto.isFree)

        assertEquals(
            listOf("inclusionai/ling-3.2-flash:free", "recursive/flint-v1.0:free", "google/lyria-3-pro-preview", "openrouter/free"),
            models.freeOnly().map { it.id },
        )
    }

    @Test
    fun `capabilities come from the entry - context, vision from image input, JSON schema from structured_outputs`() {
        val models = models()
        assertEquals(Capabilities(text = true, vision = true, jsonSchema = true, maxContext = 1_000_000), models.byId("anthropic/claude-sonnet-4.6")!!.capabilities())
        assertEquals(Capabilities(text = true, vision = false, jsonSchema = false, maxContext = 8192), models.byId("tencent/hy-mt2-1.8b")!!.capabilities())

        val flint = models.byId("recursive/flint-v1.0:free")!!
        assertTrue("response_format" in flint.supportedParameters)
        assertFalse(flint.supportsJsonSchema, "response_format alone is JSON mode, not a schema")
        assertFalse(flint.capabilities().jsonSchema)

        val image = models.byId("google/gemini-3.1-flash-image")!!
        assertEquals(setOf("image", "text"), image.outputModalities)
        assertTrue(image.capabilities().text)
    }

    @Test
    fun `filters - free, minimum context, tools, JSON schema - and lookup by id`() {
        val models = models()
        assertEquals(listOf("tencent/hy-mt2-1.8b"), models.filter { (it.contextLength ?: 0) < 100_000 }.map { it.id })
        assertFalse(models.withMinContext(100_000).any { it.id == "tencent/hy-mt2-1.8b" })
        assertEquals(11, models.withMinContext(100_000).size)
        assertTrue(models.withMinContext(2_000_000).map { it.id } == listOf("openrouter/auto"))

        val tools = models.supportingTools().map { it.id }
        assertFalse("tencent/hy-mt2-1.8b" in tools || "google/lyria-3-pro-preview" in tools)
        assertTrue("anthropic/claude-sonnet-4.6" in tools)

        val schema = models.supportingJsonSchema().map { it.id }
        assertFalse("recursive/flint-v1.0:free" in schema)
        assertTrue("meta-llama/llama-3.3-70b-instruct" in schema)

        assertEquals(listOf("inclusionai/ling-3.2-flash:free", "openrouter/free"), models.freeOnly().supportingJsonSchema().withMinContext(128_000).map { it.id })
    }

    @Test
    fun `a retired or misspelled id is not found, which is how an app spots a stale saved choice`() {
        val models = models()
        assertNull(models.byId("meta-llama/llama-3.3-70b-instruct:free"), "retired free variant")
        assertNull(models.byId("anthropic/claude-sonnet-4-6"), "the id is spelled with a dot")
        assertEquals(LocalDate.parse("2026-11-20"), models.byId("z-ai/glm-5.1")!!.expiresOn, "announced retirement")
    }

    @Test
    fun `entries without an id are skipped and unknown fields are ignored`() {
        server.onModels = {
            it.respond(200, """{"data":[{"name":"no id"},{"id":"a/b","surprise":{"x":[1]},"pricing":{"prompt":"x","completion":null}}, 7],"extra":true}""")
        }
        val model = assertIs<CatalogResult.Ok>(catalog().load()).models.single()
        assertEquals("a/b", model.id)
        assertEquals("a/b", model.name, "falls back to the id")
        assertNull(model.contextLength)
        assertNull(model.promptPricePerToken)
        assertEquals(Capabilities(), model.capabilities(), "nothing reported: the library's defaults")
    }

    @Test
    fun `HTTP errors are values with the status code`() {
        for (code in listOf(429, 500, 503, 404)) {
            server.onModels = { it.respond(code, """{"error":{"message":"nope","code":$code}}""") }
            assertEquals(CatalogResult.HttpError(code), catalog().load(), "$code")
        }
    }

    @Test
    fun `a redirect is not followed`() {
        server.onModels = {
            it.responseHeaders.add("Location", "/v1/chat/completions")
            it.respond(302, "")
        }
        assertEquals(CatalogResult.HttpError(302), catalog().load())
        assertTrue(server.chatRequests.isEmpty())
    }

    @Test
    fun `a body that is not a model list is Malformed`() {
        for (body in listOf("<html>maintenance</html>", "[]", """{"data":{}}""", """{"models":[]}""", """{"data":[1,2]}""", "")) {
            server.onModels = { it.respond(200, body) }
            assertEquals(CatalogResult.Malformed, catalog().load(), body)
        }
        server.onModels = { it.respond(200, """{"data":[]}""") }
        assertEquals(emptyList(), assertIs<CatalogResult.Ok>(catalog().load()).models, "an empty list is a list")
    }

    @Test
    fun `an enormous body is Malformed, not read into memory`() {
        val huge = "{\"data\":[" + " ".repeat((OpenRouterHttp.MAX_BODY_BYTES + 10).toInt()) + "]}"
        server.onModels = { it.respond(200, huge) }
        assertEquals(CatalogResult.Malformed, catalog().load())
    }

    @Test
    fun `nothing listening is Unreachable, quickly`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = runBlocking { OpenRouterCatalog(baseUrl = "http://127.0.0.1:$port/v1").fetch() }
        assertEquals(CatalogResult.Unreachable, result)
        assertEquals(CatalogResult.Unreachable, runBlocking { OpenRouterCatalog(baseUrl = "not a url").fetch() })
    }

    @Test
    fun `a server that does not answer in time is Unreachable at the timeout`() {
        val release = CountDownLatch(1)
        server.onModels = { release.await(10, TimeUnit.SECONDS) }
        val took = measureTime { assertEquals(CatalogResult.Unreachable, catalog(timeout = 300.milliseconds).load()) }
        release.countDown()
        assertTrue(took < 3.seconds, "took $took")
    }

    @Test
    fun `cancelling the caller cancels the HTTP call`() {
        val release = CountDownLatch(1)
        server.onModels = { release.await(10, TimeUnit.SECONDS) }
        val client = OkHttpClient()
        val took = measureTime {
            assertNull(runBlocking { withTimeoutOrNull(300.milliseconds) { catalog(client = client).fetch() } })
        }
        assertTrue(took < 3.seconds, "took $took")
        val deadline = System.nanoTime() + 3_000_000_000
        while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0, client.dispatcher.runningCallsCount(), "the call was cancelled, not left running")
        release.countDown()
    }

    @Test
    fun `a successful fetch is reused until it is older than cacheFor`() {
        val catalog = catalog(cacheFor = 10.minutes)
        val first = assertIs<CatalogResult.Ok>(catalog.load())
        clock = clock.plusSeconds(9 * 60)
        assertSame(first, catalog.load())
        assertEquals(1, server.requests.size)

        clock = clock.plusSeconds(2 * 60)
        val second = assertIs<CatalogResult.Ok>(catalog.load())
        assertEquals(2, server.requests.size)
        assertEquals(clock, second.fetchedAt)

        catalog.load(refresh = true)
        assertEquals(3, server.requests.size, "refresh skips the cache")
    }

    @Test
    fun `failures are not cached and the last good list stays available`() {
        val catalog = catalog(cacheFor = Duration.ZERO)
        assertNull(catalog.lastGood)
        val good = assertIs<CatalogResult.Ok>(catalog.load())

        server.onModels = { it.respond(503, "") }
        assertEquals(CatalogResult.HttpError(503), catalog.load())
        assertSame(good, catalog.lastGood, "for showing the list while offline")

        server.onModels = { it.respond(200, fixture) }
        assertIs<CatalogResult.Ok>(catalog.load())
        assertEquals(3, server.requests.size, "cacheFor = ZERO fetches every time")
    }

    @Test
    fun `toString of a result does not dump the whole list`() {
        val text = catalog().load().toString()
        assertEquals("Ok(12 models, fetchedAt=2026-11-15T12:00:00Z)", text)
    }
}
