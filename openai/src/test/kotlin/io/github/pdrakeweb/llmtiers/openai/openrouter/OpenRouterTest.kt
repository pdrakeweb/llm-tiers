package io.github.pdrakeweb.llmtiers.openai.openrouter

import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.JsonSchema
import io.github.pdrakeweb.llmtiers.Needs
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.SkipReason
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.openai.FakeOpenAiServer
import io.github.pdrakeweb.llmtiers.openai.respond
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenRouterTest {
    private val server = FakeOpenAiServer()
    private val hello = PreparedRequest(listOf(user("hello")))
    private val models = checkNotNull(OpenRouterCatalog.parseModels(checkNotNull(javaClass.getResource("/openrouter/models.json")).readText()))

    @AfterEach
    fun stop() = server.close()

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(15.seconds) { block() } }

    @Test
    fun `the base URL is OpenRouter's v1 API root`() {
        assertEquals("https://openrouter.ai/api/v1", OpenRouter.BASE_URL)
    }

    @Test
    fun `the provider sends the key, the model and the attribution headers`() {
        val provider = OpenRouter.provider(
            "openrouter", "anthropic/claude-sonnet-4.6", apiKey = "sk-or-test",
            appName = "Tide Tables", appUrl = "https://example.org/tides", baseUrl = server.baseUrl,
        )
        assertEquals(listOf(ProviderEvent.Token("ok"), ProviderEvent.Done(null)), run { provider.stream(hello).toList() })

        val sent = server.chatRequests.single()
        assertEquals("Bearer sk-or-test", sent.headers["authorization"])
        assertEquals("https://example.org/tides", sent.headers["http-referer"])
        assertEquals("Tide Tables", sent.headers["x-openrouter-title"])
        assertEquals("Tide Tables", sent.headers["x-title"], "the older alias, for gateways that only know it")
        assertNull(sent.headers["x-openrouter-app-visibility"])
        assertEquals("anthropic/claude-sonnet-4.6", Json.parseToJsonElement(sent.body).jsonObject.getValue("model").jsonPrimitive.content)
        assertEquals(ProviderId("openrouter"), provider.id)
        assertEquals(Tier.REMOTE, provider.tier)
    }

    @Test
    fun `without app details no attribution headers are sent`() {
        run { OpenRouter.provider("or", "a/b", apiKey = "k", baseUrl = server.baseUrl).stream(hello).toList() }
        val headers = server.chatRequests.single().headers
        assertFalse(listOf("http-referer", "x-openrouter-title", "x-title", "x-openrouter-app-visibility").any { it in headers }, "$headers")
    }

    @Test
    fun `header values are made ASCII so an app name cannot break the request`() {
        assertEquals(
            mapOf("HTTP-Referer" to "https://example.org/a", "X-OpenRouter-Title" to "Cafe Mareas", "X-Title" to "Cafe Mareas", "X-OpenRouter-App-Visibility" to "hidden"),
            OpenRouter.attributionHeaders("  Café Mareas ⛵ ", "https://example.org/a\n", appHidden = true),
        )
        assertEquals(emptyMap(), OpenRouter.attributionHeaders("⛵", " ", appHidden = false), "nothing printable left: nothing sent")

        run { OpenRouter.provider("or", "a/b", apiKey = "k", appName = "Ñandú", baseUrl = server.baseUrl).stream(hello).toList() }
        assertEquals("Nandu", server.chatRequests.single().headers["x-openrouter-title"])
    }

    @Test
    fun `the app's own extra headers win over the attribution headers`() {
        val provider = OpenRouter.provider(
            "or", "a/b", apiKey = "k", appName = "Tide Tables", baseUrl = server.baseUrl,
            extraHeaders = mapOf("X-Title" to "Override", "X-Custom" to "1"),
        )
        run { provider.stream(hello).toList() }
        val headers = server.chatRequests.single().headers
        assertEquals("Override", headers["x-title"])
        assertEquals("Tide Tables", headers["x-openrouter-title"])
        assertEquals("1", headers["x-custom"])
    }

    @Test
    fun `a catalog entry supplies the capabilities, and an explicit value overrides it`() {
        val sonnet = models.byId("anthropic/claude-sonnet-4.6")!!
        val derived = OpenRouter.provider("or", sonnet.id, apiKey = "k", catalogEntry = sonnet)
        assertEquals(Capabilities(vision = true, jsonSchema = true, maxContext = 1_000_000), run { derived.capabilities() })

        val declared = OpenRouter.provider("or", sonnet.id, apiKey = "k", catalogEntry = sonnet, capabilities = Capabilities(maxContext = 32_000))
        assertEquals(Capabilities(maxContext = 32_000), run { declared.capabilities() })

        assertEquals(Capabilities(), run { OpenRouter.provider("or", "a/b", apiKey = "k").capabilities() }, "no entry: the library's defaults")
    }

    @Test
    fun `a catalog entry for a different model is refused`() {
        val sonnet = models.byId("anthropic/claude-sonnet-4.6")!!
        assertThrows<IllegalArgumentException> { OpenRouter.provider("or", "meta-llama/llama-3.3-70b-instruct", apiKey = "k", catalogEntry = sonnet) }
    }

    @Test
    fun `defaults - a key is required and the provider is not probed before use`() {
        val provider = OpenRouter.provider("or", "a/b", apiKey = null, baseUrl = server.baseUrl)
        assertFalse(provider.probeBeforeUse)
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.KeyMissing)), run { provider.stream(hello).toList() })
        assertTrue(server.requests.isEmpty(), "nothing was sent without a key")
    }

    @Test
    fun `catalog capabilities route a JSON-schema request past a model that cannot honour it`() {
        val flint = models.byId("recursive/flint-v1.0:free")!!
        val llama = models.byId("meta-llama/llama-3.3-70b-instruct")!!
        val ai = TieredAi {
            provider(OpenRouter.provider("flint", flint.id, apiKey = "k", catalogEntry = flint, baseUrl = server.baseUrl))
            provider(OpenRouter.provider("llama", llama.id, apiKey = "k", catalogEntry = llama, baseUrl = server.baseUrl))
        }
        val request = Request(listOf(user("as JSON")), needs = Needs(structuredJson = JsonSchema("answer", """{"type":"object"}""")))
        val result = assertIs<Result.Answer>(run { ai.generate(request) })
        assertEquals(ProviderId("llama"), result.answeredBy)
        assertEquals(Outcome.Skipped(SkipReason.MissingCapability("jsonSchema")), result.attempts.first { it.provider == ProviderId("flint") }.outcome)
        assertEquals(1, server.chatRequests.size)
    }

    // --- key check -------------------------------------------------------------------------

    private fun check(key: String?) = run { OpenRouter.checkKey(key, baseUrl = server.baseUrl) }

    @Test
    fun `a working key reports its limit, usage and tier`() {
        // The documented shape of GET /key (openrouter.ai/docs, limits), with made-up figures.
        server.onKey = {
            it.respond(
                200,
                """{"data":{"label":"sk-or-v1-0e6...1c9","limit":20,"limit_reset":"monthly","limit_remaining":17.5,"include_byok_in_limit":false,
                   "usage":2.5,"usage_daily":0.5,"usage_weekly":1.25,"usage_monthly":2.5,"byok_usage":0,"is_free_tier":false,
                   "free_model_daily_requests":{"used":3,"limit":1000,"remaining":997},"rate_limit":{"requests":-1,"interval":"10s"}}}""",
            )
        }
        assertEquals(
            KeyStatus.Valid("sk-or-v1-0e6...1c9", BigDecimal("20"), BigDecimal("2.5"), BigDecimal("17.5"), "monthly", isFreeTier = false),
            check("  sk-or-v1-real  "),
        )
        val sent = server.requests.single()
        assertEquals("GET", sent.method)
        assertEquals("/v1/key", sent.path)
        assertEquals("Bearer sk-or-v1-real", sent.headers["authorization"], "trimmed")
    }

    @Test
    fun `a key with no limit reports nulls, not zero`() {
        server.onKey = { it.respond(200, """{"data":{"label":"phone","limit":null,"limit_remaining":null,"limit_reset":null,"usage":0,"is_free_tier":true}}""") }
        assertEquals(KeyStatus.Valid("phone", null, BigDecimal.ZERO, null, null, isFreeTier = true), check("k"))
    }

    @Test
    fun `a refused key is Rejected - the real 401 reply`() {
        assertEquals(KeyStatus.Rejected, check("sk-or-v1-unknown"))
        server.onKey = { it.respond(403, "") }
        assertEquals(KeyStatus.Rejected, check("k"))
    }

    @Test
    fun `no key, or one no header can carry, is never sent`() {
        assertEquals(KeyStatus.Missing, check(null))
        assertEquals(KeyStatus.Missing, check("   "))
        assertEquals(KeyStatus.Rejected, check("sk-or-v1-ab cd"))
        assertEquals(KeyStatus.Rejected, check("sk-or-v1-é"))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `other failures are values`() {
        server.onKey = { it.respond(429, "") }
        assertEquals(KeyStatus.HttpError(429), check("k"))
        server.onKey = { it.respond(502, "") }
        assertEquals(KeyStatus.HttpError(502), check("k"))
        server.onKey = { it.respond(200, "<html></html>") }
        assertEquals(KeyStatus.Malformed, check("k"))
        server.onKey = { it.respond(200, """{"error":"x"}""") }
        assertEquals(KeyStatus.Malformed, check("k"))

        val port = ServerSocket(0).use { it.localPort }
        assertEquals(KeyStatus.Unreachable, run { OpenRouter.checkKey("k", baseUrl = "http://127.0.0.1:$port/v1") })
    }
}
