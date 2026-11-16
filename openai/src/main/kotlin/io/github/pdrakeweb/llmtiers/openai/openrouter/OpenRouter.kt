package io.github.pdrakeweb.llmtiers.openai.openrouter

import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.openai.OpenAiCompatible
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.text.Normalizer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Helpers for [OpenRouter](https://openrouter.ai), a cloud gateway to several hundred models
 * behind one OpenAI-compatible API.
 *
 * - [provider] builds the [OpenAiCompatible] provider with OpenRouter's attribution headers and,
 *   when given a catalog entry, the model's real capabilities.
 * - [OpenRouterCatalog] fetches the live model list, so an app never ships a hard-coded list of
 *   model ids that goes stale.
 * - [checkKey] tells a settings screen whether a key works before the first request.
 */
public object OpenRouter {
    /** The API root, including the version segment. */
    public const val BASE_URL: String = "https://openrouter.ai/api/v1"

    /**
     * An [OpenAiCompatible] provider for OpenRouter.
     *
     * @param model the OpenRouter model id, for example `anthropic/claude-sonnet-4.6`. Take it from
     *   [OpenRouterCatalog] rather than from a list in the app: ids are retired and renamed.
     * @param catalogEntry the catalog entry for [model], if the app has one. Its capabilities
     *   (context length, vision, JSON-schema output) become the default for [capabilities]. It
     *   must describe [model].
     * @param appName sent as `X-OpenRouter-Title` (and its older alias `X-Title`): the name
     *   OpenRouter shows for the app. Non-ASCII letters lose their accents; other non-ASCII
     *   characters are dropped, because HTTP header values are ASCII.
     * @param appUrl sent as `HTTP-Referer`: the app's URL, which OpenRouter uses to identify the
     *   app. Without it OpenRouter creates no app page and [appName] is not shown anywhere.
     * @param appHidden asks OpenRouter to create the app page hidden from its public rankings
     *   (`X-OpenRouter-App-Visibility: hidden`). It applies only to the request that creates the
     *   page; an existing page keeps its visibility.
     *
     * The remaining parameters are [OpenAiCompatible]'s and mean the same. [extraHeaders] are
     * added after the attribution headers and win over them.
     */
    public fun provider(
        id: String,
        model: String,
        apiKey: String?,
        catalogEntry: OpenRouterModel? = null,
        appName: String? = null,
        appUrl: String? = null,
        appHidden: Boolean = false,
        capabilities: Capabilities = catalogEntry?.capabilities() ?: Capabilities(),
        accepts: Set<Sensitivity> = setOf(Sensitivity.Public, Sensitivity.Personal),
        requiresKey: Boolean = true,
        probeBeforeUse: Boolean = false,
        client: OkHttpClient = OkHttpClient(),
        extraHeaders: Map<String, String> = emptyMap(),
        maxTokens: Int? = null,
        temperature: Double? = null,
        includeUsage: Boolean = true,
        probeTimeout: Duration = 1.seconds,
        baseUrl: String = BASE_URL,
    ): OpenAiCompatible {
        require(catalogEntry == null || catalogEntry.id == model) {
            "catalogEntry describes ${catalogEntry?.id}, not $model"
        }
        return OpenAiCompatible(
            id = id,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            requiresKey = requiresKey,
            probeBeforeUse = probeBeforeUse,
            accepts = accepts,
            capabilities = capabilities,
            client = client,
            extraHeaders = attributionHeaders(appName, appUrl, appHidden) + extraHeaders,
            maxTokens = maxTokens,
            temperature = temperature,
            includeUsage = includeUsage,
            probeTimeout = probeTimeout,
        )
    }

    /**
     * Checks a key with `GET {baseUrl}/key`, which costs nothing and sends no prompt. Use it from
     * a settings screen after the user enters a key. It never throws for network or HTTP
     * failures, and stops when the calling coroutine is cancelled. The key is sent only as the
     * `Authorization` header and is never logged or kept.
     */
    public suspend fun checkKey(
        apiKey: String?,
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = BASE_URL,
        timeout: Duration = 10.seconds,
    ): KeyStatus {
        val key = apiKey?.trim()
        if (key.isNullOrEmpty()) return KeyStatus.Missing
        // A key with characters an HTTP header cannot carry was not issued by OpenRouter. It is
        // not sent: OkHttp would refuse to build the request.
        if (key.any { it !in '!'..'~' }) return KeyStatus.Rejected
        return when (val reply = OpenRouterHttp.get(client, "${baseUrl.trimEnd('/')}/key", key, timeout)) {
            OpenRouterHttp.Reply.Unreachable -> KeyStatus.Unreachable
            OpenRouterHttp.Reply.TooLarge -> KeyStatus.Malformed
            is OpenRouterHttp.Reply.Status -> when (reply.code) {
                200 -> parseKeyStatus(reply.body)
                401, 403 -> KeyStatus.Rejected
                else -> KeyStatus.HttpError(reply.code)
            }
        }
    }

    internal fun attributionHeaders(appName: String?, appUrl: String?, appHidden: Boolean): Map<String, String> = buildMap {
        appUrl?.let(::headerSafe)?.takeIf { it.isNotEmpty() }?.let { put("HTTP-Referer", it) }
        appName?.let(::headerSafe)?.takeIf { it.isNotEmpty() }?.let {
            put("X-OpenRouter-Title", it)
            put("X-Title", it) // the older name, still honoured by OpenRouter
        }
        if (appHidden) put("X-OpenRouter-App-Visibility", "hidden")
    }

    /** OkHttp rejects header values outside printable ASCII at request time; clean them up front. */
    private fun headerSafe(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .filter { it in ' '..'~' }
            .trim()

    private fun parseKeyStatus(body: String): KeyStatus {
        val data = OpenRouterHttp.parseObject(body)?.get("data") as? JsonObject ?: return KeyStatus.Malformed
        return KeyStatus.Valid(
            label = data.string("label").orEmpty(),
            limit = data.decimal("limit"),
            usage = data.decimal("usage") ?: BigDecimal.ZERO,
            limitRemaining = data.decimal("limit_remaining"),
            limitReset = data.string("limit_reset"),
            isFreeTier = data.boolean("is_free_tier") ?: false,
        )
    }
}

/** What OpenRouter says about a key. */
public sealed interface KeyStatus {
    /**
     * The key works. Amounts are in US dollars of OpenRouter credit.
     *
     * @property label OpenRouter's name for the key: the name the owner gave it, or a masked
     *   form such as `sk-or-v1-abc...xyz`. Never the key itself.
     * @property limit the key's credit limit, or null when it has none.
     * @property usage credit used by the key so far.
     * @property limitRemaining credit left under [limit], or null when there is no limit.
     * @property limitReset how often the limit resets (for example `monthly`), or null for never.
     * @property isFreeTier true when the account has never bought credit. Free models then have
     *   a lower daily request allowance.
     */
    public data class Valid(
        val label: String,
        val limit: BigDecimal?,
        val usage: BigDecimal,
        val limitRemaining: BigDecimal?,
        val limitReset: String?,
        val isFreeTier: Boolean,
    ) : KeyStatus

    /** No key was given. Nothing was sent. */
    public data object Missing : KeyStatus

    /** OpenRouter refused the key (401 or 403). */
    public data object Rejected : KeyStatus

    /** OpenRouter could not be reached, or did not answer within the timeout. */
    public data object Unreachable : KeyStatus

    /** Any other HTTP status, for example 429 or 5xx. */
    public data class HttpError(val code: Int) : KeyStatus

    /** A 200 reply that was not the expected JSON. */
    public data object Malformed : KeyStatus
}
