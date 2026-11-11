package io.github.pdrakeweb.llmtiers.openai

import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.Event
import io.github.pdrakeweb.llmtiers.Item
import io.github.pdrakeweb.llmtiers.JsonSchema
import io.github.pdrakeweb.llmtiers.Message
import io.github.pdrakeweb.llmtiers.Needs
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.Part
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.Role
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.TimeoutConfig
import io.github.pdrakeweb.llmtiers.Usage
import io.github.pdrakeweb.llmtiers.openai.FakeOpenAiServer.Companion.chunk
import io.github.pdrakeweb.llmtiers.openai.FakeOpenAiServer.Companion.finish
import io.github.pdrakeweb.llmtiers.openai.FakeOpenAiServer.Companion.usage
import io.github.pdrakeweb.llmtiers.system
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class OpenAiCompatibleTest {
    private val server = FakeOpenAiServer()
    private val hello = PreparedRequest(listOf(system("be brief"), user("hello")))

    @AfterEach
    fun stop() = server.close()

    private fun provider(
        tier: Tier = Tier.LAN,
        apiKey: String? = null,
        baseUrl: String = server.baseUrl,
        capabilities: Capabilities = Capabilities(maxContext = 8192),
        client: OkHttpClient = OkHttpClient(),
    ) = OpenAiCompatible("p", baseUrl, "test-model", tier, apiKey = apiKey, capabilities = capabilities, client = client)

    private fun OpenAiCompatible.collect(request: PreparedRequest = hello): List<ProviderEvent> =
        runBlocking { withTimeout(15.seconds) { stream(request).toList() } }

    private fun body(index: Int = 0) = Json.parseToJsonElement(server.chatRequests[index].body).jsonObject

    @Test
    fun `streams tokens in order and ends with Done and usage`() {
        server.onChat = { it.sse(chunk("Hel"), chunk("lo"), chunk(" world"), finish(), usage(12, 3), "[DONE]") }
        val events = provider().collect()
        assertEquals(
            listOf(
                ProviderEvent.Token("Hel"), ProviderEvent.Token("lo"), ProviderEvent.Token(" world"),
                ProviderEvent.Done(Usage(12, 3)),
            ),
            events,
        )
    }

    @Test
    fun `tokens arrive while the response is still open - it really streams`() {
        val release = CountDownLatch(1)
        server.onChat = {
            it.startSse()
            it.event(chunk("first"))
            release.await(10, TimeUnit.SECONDS)
            it.event(chunk("second"))
            it.event("[DONE]")
        }
        runBlocking {
            withTimeout(15.seconds) {
                val seen = mutableListOf<ProviderEvent>()
                provider().stream(hello).collect { event ->
                    seen += event
                    if (event == ProviderEvent.Token("first")) {
                        assertEquals(1, seen.size, "the first token was delivered before the server sent the rest")
                        release.countDown()
                    }
                }
                assertEquals(listOf(ProviderEvent.Token("first"), ProviderEvent.Token("second"), ProviderEvent.Done(null)), seen)
            }
        }
    }

    @Test
    fun `sends an OpenAI chat-completions request`() {
        provider(apiKey = "sk-test-123").collect()
        val request = server.chatRequests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test-123", request.headers["authorization"])
        val json = body()
        assertEquals("test-model", json.getValue("model").jsonPrimitive.content)
        assertTrue(json.getValue("stream").jsonPrimitive.boolean)
        val messages = json.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("hello", messages[1].getValue("content").jsonPrimitive.content)
        assertNull(json["response_format"])
    }

    @Test
    fun `no Authorization header without a key`() {
        provider(apiKey = null).collect()
        assertNull(server.chatRequests.single().headers["authorization"])
    }

    @Test
    fun `data items are sent as a context block in the last user message`() {
        val request = PreparedRequest(
            listOf(user("plan my session")),
            items = listOf(Item("30 minutes", Sensitivity.Public, "limit"), Item("sore knee", Sensitivity.Personal)),
        )
        provider().collect(request)
        val content = body().getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue("- limit: 30 minutes" in content && "- sore knee" in content && content.endsWith("plan my session"), content)
    }

    @Test
    fun `a JSON schema need becomes response_format json_schema`() {
        val schema = """{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}"""
        provider().collect(PreparedRequest(listOf(user("x")), needs = Needs(structuredJson = JsonSchema("routine", schema))))
        val format = body().getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val inner = format.getValue("json_schema").jsonObject
        assertEquals("routine", inner.getValue("name").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(schema), inner.getValue("schema"))
    }

    @Test
    fun `an image part is sent as a base64 data URL`() {
        val image = Part.Image(byteArrayOf(1, 2, 3), "image/png")
        provider().collect(PreparedRequest(listOf(Message(Role.User, listOf(Part.Text("what is this?"), image)))))
        val parts = body().getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url"), parts.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("data:image/png;base64,AQID", parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `the idempotency key is sent as a header`() {
        provider().collect(PreparedRequest(listOf(user("x")), idempotencyKey = "abc-123"))
        assertEquals("abc-123", server.chatRequests.single().headers["idempotency-key"])
    }

    @Test
    fun `comments, keep-alives, reasoning deltas and empty content are not tokens`() {
        server.onChat = {
            it.startSse()
            it.raw(": OPENROUTER PROCESSING\n\n")
            it.event("""{"choices":[{"delta":{"role":"assistant","content":""}}]}""")
            it.event("""{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}""")
            it.event("""{"choices":[{"delta":{"content":null}}]}""")
            it.event(chunk("answer"))
            it.event("[DONE]")
        }
        assertEquals(listOf(ProviderEvent.Token("answer"), ProviderEvent.Done(null)), provider().collect())
    }

    @Test
    fun `a stream that closes after finish_reason without DONE is complete`() {
        server.onChat = { it.sse(chunk("hi"), finish()) }
        assertEquals(listOf(ProviderEvent.Token("hi"), ProviderEvent.Done(null)), provider().collect())
    }

    @Test
    fun `a stream that just stops is a failure, not a silent short answer`() {
        server.onChat = { it.sse(chunk("hi")) }
        val events = provider().collect()
        assertEquals(ProviderEvent.Token("hi"), events.first())
        assertIs<ProviderFailure.Other>(assertIs<ProviderEvent.Failed>(events.last()).failure)
    }

    @Test
    fun `HTTP errors map to typed failures and never carry the response text`() {
        val secret = "SECRET-ECHO-OF-PROMPT"
        val cases = listOf(
            401 to ProviderFailure.AuthRejected,
            403 to ProviderFailure.AuthRejected,
            429 to ProviderFailure.RateLimited,
            500 to ProviderFailure.Server(500),
            503 to ProviderFailure.Server(503),
            504 to ProviderFailure.Timeout,
            404 to ProviderFailure.Other("http 404"),
            400 to ProviderFailure.Other("http 400"),
        )
        for ((code, expected) in cases) {
            server.onChat = { it.respond(code, """{"error":{"message":"bad: $secret"}}""") }
            val event = provider().collect().single()
            assertEquals(ProviderEvent.Failed(expected), event, "HTTP $code")
            assertFalse(secret in event.toString())
        }
    }

    @Test
    fun `a context-length error becomes ContextTooLong with this provider's limit`() {
        val bodies = listOf(
            """{"error":{"message":"This model's maximum context length is 8192 tokens.","code":"context_length_exceeded"}}""",
            """{"error":{"code":400,"message":"the request exceeds the available context size","type":"exceed_context_size_error"}}""",
        )
        for (b in bodies) {
            server.onChat = { it.respond(400, b) }
            assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.ContextTooLong(8192))), provider().collect())
        }
    }

    @Test
    fun `an error object inside the stream is a failure`() {
        server.onChat = { it.sse(chunk("par"), """{"error":{"code":429,"message":"slow down"}}""") }
        assertEquals(
            listOf(ProviderEvent.Token("par"), ProviderEvent.Failed(ProviderFailure.RateLimited)),
            provider().collect(),
        )
    }

    @Test
    fun `malformed stream data is a typed failure`() {
        server.onChat = { it.sse("{not json") }
        assertIs<ProviderFailure.Other>(assertIs<ProviderEvent.Failed>(provider().collect().single()).failure)
    }

    @Test
    fun `a cloud provider without a key fails with KeyMissing and makes no request`() {
        val p = provider(tier = Tier.CLOUD, apiKey = " ")
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.KeyMissing)), p.collect())
        assertEquals(Reachability.Unreachable(ProviderFailure.KeyMissing), runBlocking { p.probe() })
        assertTrue(server.requests.isEmpty())
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `nothing listening is Unreachable`() {
        val p = provider(baseUrl = "http://127.0.0.1:${closedPort()}/v1")
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.Unreachable)), p.collect())
        assertEquals(Reachability.Unreachable(ProviderFailure.Unreachable), runBlocking { p.probe() })
    }

    @Test
    fun `a server that accepts the request and never answers ends as FirstTokenTimeout, not Unreachable`() {
        // The distinction matters: Unreachable proves the request was not processed; this does not.
        val hold = CountDownLatch(1)
        server.onChat = { hold.await(10, TimeUnit.SECONDS) }
        val ai = TieredAi {
            provider(provider())
            timeouts = TimeoutConfig(firstToken = 1.seconds)
        }
        val result = runBlocking { withTimeout(10.seconds) { ai.generate(Request(listOf(user("hi")))) } }
        hold.countDown()
        assertIs<Result.NoneAvailable>(result)
        assertEquals(Outcome.Failed(ProviderFailure.FirstTokenTimeout), result.attempts.single().outcome)
    }

    @Test
    fun `cancelling the collector cancels the HTTP call`() {
        val clientGone = CountDownLatch(1)
        server.onChat = {
            it.startSse()
            try {
                while (true) {
                    it.event(chunk("x"))
                    Thread.sleep(20)
                }
            } catch (e: Exception) {
                clientGone.countDown()
                throw e
            }
        }
        runBlocking { withTimeout(10.seconds) { assertEquals(ProviderEvent.Token("x"), provider().stream(hello).first()) } }
        assertTrue(clientGone.await(10, TimeUnit.SECONDS), "the server saw the connection close")
    }

    @Test
    fun `probe is GET models - reachable, auth rejected, server error`() {
        val p = provider(apiKey = "k")
        assertEquals(Reachability.Reachable, runBlocking { p.probe() })
        assertEquals("GET", server.requests.single().method)
        assertEquals("/v1/models", server.requests.single().path)
        assertEquals("Bearer k", server.requests.single().headers["authorization"])

        server.onModels = { it.respond(401, "{}") }
        assertEquals(Reachability.Unreachable(ProviderFailure.AuthRejected), runBlocking { p.probe() })
        server.onModels = { it.respond(502, "{}") }
        assertEquals(Reachability.Unreachable(ProviderFailure.Server(502)), runBlocking { p.probe() })
        server.onModels = { it.respond(404, "{}") }
        assertEquals(Reachability.Reachable, runBlocking { p.probe() }, "it answered; a server without /models can still chat")
    }

    @Test
    fun `a probe that gets no answer gives up after about a second`() {
        val hold = CountDownLatch(1)
        server.onModels = { hold.await(10, TimeUnit.SECONDS) }
        val p = provider()
        val took = measureTime { assertEquals(Reachability.Unreachable(ProviderFailure.Unreachable), runBlocking { p.probe() }) }
        hold.countDown()
        assertTrue(took < 1.5.seconds, "probe took $took")
    }

    @Test
    fun `end to end - LAN server gone, the cloud endpoint answers, and the result says so`() {
        server.onChat = { it.sse(chunk("from "), chunk("cloud"), finish(), "[DONE]") }
        val lan = OpenAiCompatible("home-llama", "http://127.0.0.1:${closedPort()}/v1", "m", Tier.LAN)
        val cloud = OpenAiCompatible("openrouter", server.baseUrl, "m", Tier.CLOUD, apiKey = "k")
        val ai = TieredAi {
            provider(lan)
            provider(cloud)
        }
        val events = runBlocking { withTimeout(15.seconds) { ai.stream(Request(listOf(user("hi")))).toList() } }
        val result = (events.last() as Event.Completed).result

        assertIs<Result.Answer>(result)
        assertEquals("from cloud", result.text)
        assertEquals(ProviderId("openrouter"), result.answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.Unreachable), result.attempts.first().outcome)
        assertTrue(Event.Token("from ") in events && Event.Token("cloud") in events)
        assertTrue(events.any { it is Event.FellBack })
    }
}
