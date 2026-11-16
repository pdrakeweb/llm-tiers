package io.github.pdrakeweb.llmtiers.openai.openrouter

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * One bounded GET for the OpenRouter helpers. Failures are values, the call is cancelled with the
 * coroutine, redirects are not followed (as in `OpenAiCompatible`, D-018), and nothing is logged.
 */
internal object OpenRouterHttp {
    sealed interface Reply {
        data object Unreachable : Reply
        data object TooLarge : Reply

        /** [body] is read only for a 2xx reply; it is empty otherwise. */
        class Status(val code: Int, val body: String) : Reply
    }

    /** The full model list is under 1 MB today; anything far larger is not what was asked for. */
    const val MAX_BODY_BYTES: Long = 16L * 1024 * 1024

    suspend fun get(client: OkHttpClient, url: String, bearer: String?, timeout: Duration): Reply {
        val target = url.toHttpUrlOrNull() ?: return Reply.Unreachable
        val request = Request.Builder()
            .url(target)
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .get()
            .build()
        val http = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Reply.Unreachable)
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = response.use {
                        if (!it.isSuccessful) {
                            Reply.Status(it.code, "")
                        } else {
                            try {
                                read(it)
                            } catch (_: IOException) {
                                Reply.Unreachable // the connection dropped or timed out mid-body
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(reply)
                }
            })
        }
    }

    private fun read(response: Response): Reply {
        val source = response.body.source()
        if (source.request(MAX_BODY_BYTES + 1)) return Reply.TooLarge
        return Reply.Status(response.code, source.buffer.readUtf8())
    }

    fun parseObject(body: String): JsonObject? =
        try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null // kotlinx.serialization's parse errors are IllegalArgumentExceptions
        }
}

/** A JSON string field, or null when it is missing, null or not a string. */
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number (or numeric string) as an exact decimal, or null. */
internal fun JsonObject.decimal(name: String): BigDecimal? = (this[name] as? JsonPrimitive)?.content?.toBigDecimalOrNull()

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

/** A JSON array of strings, in order, ignoring anything that is not a string. */
internal fun JsonObject.strings(name: String): Set<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet().orEmpty()
