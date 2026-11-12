package io.github.pdrakeweb.llmtiers.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A real HTTP server on loopback that plays an OpenAI-compatible endpoint from a script. */
class FakeOpenAiServer : AutoCloseable {
    class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "fake-openai").apply { isDaemon = true } }
    val requests = CopyOnWriteArrayList<Recorded>()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    /** What `POST /v1/chat/completions` does. */
    @Volatile var onChat: (HttpExchange) -> Unit = { it.sse(chunk("ok"), finish(), "[DONE]") }

    /** What `GET /v1/models` does. */
    @Volatile var onModels: (HttpExchange) -> Unit = { it.respond(200, """{"data":[{"id":"test-model"}]}""") }

    init {
        server.executor = executor
        server.createContext("/v1/chat/completions") { handle(it) { e -> onChat(e) } }
        server.createContext("/v1/models") { handle(it) { e -> onModels(e) } }
        server.start()
    }

    private fun handle(exchange: HttpExchange, block: (HttpExchange) -> Unit) {
        try {
            requests += Recorded(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                exchange.requestBody.readBytes().decodeToString(),
            )
            block(exchange)
        } catch (_: Exception) {
            // The client went away mid-response; that is what some tests are about.
        } finally {
            exchange.close()
        }
    }

    val chatRequests: List<Recorded> get() = requests.filter { it.path.endsWith("/chat/completions") }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun chunk(text: String): String =
            """{"choices":[{"index":0,"delta":{"content":${quote(text)}},"finish_reason":null}]}"""

        fun finish(): String = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""

        fun usage(prompt: Int, completion: Int): String =
            """{"choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion}}"""

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

fun HttpExchange.respond(code: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/** Sends each payload as one server-sent event, flushing after each. */
fun HttpExchange.sse(vararg payloads: String) {
    startSse()
    payloads.forEach(::event)
}

fun HttpExchange.startSse() {
    responseHeaders.add("Content-Type", "text/event-stream")
    sendResponseHeaders(200, 0)
    responseBody.flush()
}

fun HttpExchange.event(payload: String) {
    responseBody.write("data: $payload\n\n".toByteArray())
    responseBody.flush()
}

fun HttpExchange.raw(text: String) {
    responseBody.write(text.toByteArray())
    responseBody.flush()
}
