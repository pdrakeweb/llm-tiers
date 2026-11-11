package io.github.pdrakeweb.llmtiers.openai

import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.Message
import io.github.pdrakeweb.llmtiers.Part
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.Provider
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Role
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.Usage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A provider for any server that speaks the OpenAI chat-completions API with streaming:
 * OpenRouter, LiteLLM, llama.cpp server, Ollama, vLLM.
 *
 * It never logs, and failures carry status codes and exception types only, never response text.
 *
 * @param baseUrl the API root, including the version segment (for example `http://192.168.1.20:4000/v1`).
 * @param capabilities what this server and model can do; `/v1/models` does not report it reliably,
 *   so the app declares it.
 * @param requiresKey when true and [apiKey] is blank the provider fails with `KeyMissing` without
 *   touching the network.
 * @param client an OkHttp client to share connection pools with the app. Its event-listener
 *   factory is replaced for this provider's calls.
 */
public class OpenAiCompatible(
    id: String,
    baseUrl: String,
    public val model: String,
    override val tier: Tier,
    private val apiKey: String? = null,
    private val requiresKey: Boolean = tier == Tier.CLOUD,
    override val accepts: Set<Sensitivity> = setOf(Sensitivity.Public, Sensitivity.Personal),
    private val capabilities: Capabilities = Capabilities(),
    client: OkHttpClient = OkHttpClient(),
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val maxTokens: Int? = null,
    private val temperature: Double? = null,
    private val includeUsage: Boolean = true,
    probeTimeout: Duration = 1.seconds,
) : Provider {
    override val id: ProviderId = ProviderId(id)

    private val root = baseUrl.trimEnd('/')

    // The executor owns first-token and stall timeouts and cancels the call. The read timeout here
    // is only a backstop, set above the executor's largest stall timeout (60 s).
    private val streamClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(maxOf(client.readTimeoutMillis, 90_000).toLong(), TimeUnit.MILLISECONDS)
        .build()
    private val probeClient = client.newBuilder()
        .callTimeout(probeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun capabilities(): Capabilities = capabilities

    /** `GET {baseUrl}/models`, bounded by the probe timeout. */
    override suspend fun probe(): Reachability {
        if (requiresKey && apiKey.isNullOrBlank()) return Reachability.Unreachable(ProviderFailure.KeyMissing)
        val request = Request.Builder().url("$root/models").withAuth().get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = probeClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Reachability.Unreachable(ProviderFailure.Unreachable))
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    response.close()
                    val result = when {
                        code == 401 || code == 403 -> Reachability.Unreachable(ProviderFailure.AuthRejected)
                        code >= 500 -> Reachability.Unreachable(ProviderFailure.Server(code))
                        else -> Reachability.Reachable // it answered; the request itself decides the rest
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    override fun stream(request: PreparedRequest): Flow<ProviderEvent> {
        if (requiresKey && apiKey.isNullOrBlank()) return flowOf(ProviderEvent.Failed(ProviderFailure.KeyMissing))
        return callbackFlow {
            val sent = AtomicBoolean(false)
            val http = Request.Builder()
                .url("$root/chat/completions")
                .withAuth()
                .header("Accept", "text/event-stream")
                .apply { request.idempotencyKey?.let { header("Idempotency-Key", it) } }
                .post(body(request).toString().toRequestBody(JSON))
                .build()
            val call = streamClient.newBuilder()
                .eventListener(object : EventListener() {
                    override fun requestHeadersStart(call: Call) = sent.set(true)
                })
                .build()
                .newCall(http)

            fun finish(event: ProviderEvent) {
                trySendBlocking(event)
                close()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) close() else finish(ProviderEvent.Failed(ioFailure(e, sent.get())))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) finish(ProviderEvent.Failed(httpFailure(response))) else finish(readStream(response))
                        } catch (e: IOException) {
                            if (call.isCanceled()) close() else finish(ProviderEvent.Failed(ioFailure(e, sent = true)))
                        } catch (e: RuntimeException) {
                            // Malformed JSON and the like. Type only; the message could quote the response.
                            finish(ProviderEvent.Failed(ProviderFailure.Other(e::class.simpleName ?: "exception")))
                        }
                    }
                }

                /** Reads server-sent events until `[DONE]`, sending tokens as they arrive. */
                private fun readStream(response: Response): ProviderEvent {
                    val source = response.body.source()
                    var usage: Usage? = null
                    var finished = false
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue // blank separators, comments, keep-alives
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") return ProviderEvent.Done(usage)
                        if (data.isEmpty()) continue
                        val chunk = Json.parseToJsonElement(data) as? JsonObject ?: continue
                        chunk["error"]?.let { if (it is JsonObject) return ProviderEvent.Failed(streamError(it)) }
                        (chunk["usage"] as? JsonObject)?.let { u ->
                            usage = Usage(
                                promptTokens = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
                                completionTokens = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull,
                            )
                        }
                        val choice = (chunk["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject ?: continue
                        val text = ((choice["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!text.isNullOrEmpty() && trySendBlocking(ProviderEvent.Token(text)).isFailure) {
                            return ProviderEvent.Done(usage) // collector is gone; nothing is listening
                        }
                        if ((choice["finish_reason"] as? JsonPrimitive)?.isString == true) finished = true
                    }
                    // Some servers close the stream after the final chunk without sending [DONE].
                    return if (finished) ProviderEvent.Done(usage) else ProviderEvent.Failed(ProviderFailure.Other("stream closed early"))
                }
            })
            awaitClose { call.cancel() }
        }
    }

    private fun Request.Builder.withAuth(): Request.Builder = apply {
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (name, value) -> header(name, value) }
    }

    private fun body(request: PreparedRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
        maxTokens?.let { put("max_tokens", it) }
        temperature?.let { put("temperature", it) }
        putJsonArray("messages") {
            for (message in request.renderedMessages()) add(message.toJson())
        }
        request.needs.structuredJson?.let { schema ->
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", schema.name)
                    put("strict", true)
                    put("schema", Json.parseToJsonElement(schema.schema))
                }
            }
        }
    }

    private fun Message.toJson(): JsonObject = buildJsonObject {
        put(
            "role",
            when (role) {
                Role.System -> "system"
                Role.User -> "user"
                Role.Assistant -> "assistant"
            },
        )
        if (parts.all { it is Part.Text }) {
            put("content", parts.joinToString("") { (it as Part.Text).text })
        } else {
            putJsonArray("content") {
                for (part in parts) {
                    when (part) {
                        is Part.Text -> addJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        }
                        is Part.Image -> addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${part.mimeType};base64," + Base64.getEncoder().encodeToString(part.bytes))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun httpFailure(response: Response): ProviderFailure {
        val code = response.code
        return when {
            code == 401 || code == 403 -> ProviderFailure.AuthRejected
            code == 429 -> ProviderFailure.RateLimited
            code == 408 || code == 504 -> ProviderFailure.Timeout
            code >= 500 -> ProviderFailure.Server(code)
            code == 400 || code == 413 || code == 422 -> {
                // The body is read only to recognise a context-length error; it is never kept.
                val text = runCatching { response.peekBody(64 * 1024).string().lowercase() }.getOrDefault("")
                if (CONTEXT_MARKERS.any { it in text }) ProviderFailure.ContextTooLong(capabilities.maxContext) else ProviderFailure.Other("http $code")
            }
            else -> ProviderFailure.Other("http $code")
        }
    }

    /** An `error` object inside an otherwise successful stream. */
    private fun streamError(error: JsonObject): ProviderFailure {
        val code = error["code"]?.let { (it as? JsonPrimitive) }
        val text = (code?.content.orEmpty() + " " + ((error["message"] as? JsonPrimitive)?.content.orEmpty())).lowercase()
        return when {
            CONTEXT_MARKERS.any { it in text } -> ProviderFailure.ContextTooLong(capabilities.maxContext)
            code?.intOrNull == 429 -> ProviderFailure.RateLimited
            code?.intOrNull == 401 || code?.intOrNull == 403 -> ProviderFailure.AuthRejected
            (code?.intOrNull ?: 0) >= 500 -> ProviderFailure.Server(code!!.intOrNull!!)
            else -> ProviderFailure.Other("stream error")
        }
    }

    /**
     * [sent] false means the failure happened before any request bytes were written, which proves
     * the server did not process the request; only then is it `Unreachable`.
     */
    private fun ioFailure(e: IOException, sent: Boolean): ProviderFailure = when {
        !sent -> ProviderFailure.Unreachable
        e is SocketTimeoutException -> ProviderFailure.Timeout
        else -> ProviderFailure.Other(e::class.simpleName ?: "IOException")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val CONTEXT_MARKERS = listOf(
            "context_length_exceeded", "context length", "context window", "maximum context",
            "exceed_context_size", "too many tokens", "prompt is too long",
        )
    }
}
