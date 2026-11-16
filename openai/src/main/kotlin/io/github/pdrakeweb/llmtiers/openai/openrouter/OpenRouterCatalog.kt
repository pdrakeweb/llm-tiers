package io.github.pdrakeweb.llmtiers.openai.openrouter

import io.github.pdrakeweb.llmtiers.Capabilities
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * OpenRouter's live model list, from `GET {baseUrl}/models`. The endpoint is public: no key is
 * sent.
 *
 * Use it for a model picker and to check a saved model id before use, instead of a list of ids
 * in the app. OpenRouter retires and renames models (a `:free` variant disappears, `4-6` becomes
 * `4.6`), and a hard-coded id then fails every request.
 *
 * Unlike most OpenAI-compatible servers, OpenRouter's list reports what each model can do, so
 * [OpenRouterModel.capabilities] can stand in for capabilities the app would otherwise declare.
 *
 * @param cacheFor how long a successful fetch is reused by [fetch]. `Duration.ZERO` turns the
 *   cache off. Failures are never cached.
 * @param timeout bounds the whole call, connect to last byte.
 * @param client an OkHttp client to share connection pools with the app. Redirects are not
 *   followed whatever the client says.
 */
public class OpenRouterCatalog(
    private val client: OkHttpClient = OkHttpClient(),
    baseUrl: String = OpenRouter.BASE_URL,
    private val cacheFor: Duration = 1.hours,
    private val timeout: Duration = 15.seconds,
    private val now: () -> Instant = Instant::now,
) {
    private val url = "${baseUrl.trimEnd('/')}/models"

    @Volatile
    private var last: CatalogResult.Ok? = null

    /**
     * The last successful fetch, however old, or null if none has succeeded in this instance.
     * For showing the list while offline after [fetch] failed.
     */
    public val lastGood: CatalogResult.Ok? get() = last

    /**
     * The model list. Returns the cached list while it is younger than `cacheFor`, unless
     * [refresh] is true. Never throws for network, HTTP or parsing failures; it is cancelled with
     * the calling coroutine.
     */
    public suspend fun fetch(refresh: Boolean = false): CatalogResult {
        val cached = last
        if (!refresh && cached != null && isFresh(cached)) return cached
        val result = when (val reply = OpenRouterHttp.get(client, url, bearer = null, timeout)) {
            OpenRouterHttp.Reply.Unreachable -> CatalogResult.Unreachable
            OpenRouterHttp.Reply.TooLarge -> CatalogResult.Malformed
            is OpenRouterHttp.Reply.Status ->
                if (reply.code != 200) {
                    CatalogResult.HttpError(reply.code)
                } else {
                    parseModels(reply.body)?.let { CatalogResult.Ok(it, now()) } ?: CatalogResult.Malformed
                }
        }
        if (result is CatalogResult.Ok) last = result
        return result
    }

    private fun isFresh(cached: CatalogResult.Ok): Boolean {
        val age = now().toEpochMilli() - cached.fetchedAt.toEpochMilli()
        return age >= 0 && age < cacheFor.inWholeMilliseconds
    }

    internal companion object {
        /** Null when the body is not a model list. Entries without an id are skipped. */
        fun parseModels(body: String): List<OpenRouterModel>? {
            val data = OpenRouterHttp.parseObject(body)?.get("data") as? JsonArray ?: return null
            val models = data.mapNotNull { (it as? JsonObject)?.let(::parseModel) }
            return if (models.isEmpty() && data.isNotEmpty()) null else models
        }

        private fun parseModel(entry: JsonObject): OpenRouterModel? {
            val id = entry.string("id")?.takeIf { it.isNotBlank() } ?: return null
            val architecture = entry["architecture"] as? JsonObject
            val pricing = entry["pricing"] as? JsonObject
            val top = entry["top_provider"] as? JsonObject
            return OpenRouterModel(
                id = id,
                name = entry.string("name") ?: id,
                description = entry.string("description").orEmpty(),
                contextLength = entry.int("context_length") ?: top?.int("context_length"),
                promptPricePerToken = pricing?.price("prompt"),
                completionPricePerToken = pricing?.price("completion"),
                supportedParameters = entry.strings("supported_parameters"),
                inputModalities = architecture?.strings("input_modalities").orEmpty(),
                outputModalities = architecture?.strings("output_modalities").orEmpty(),
                created = entry.long("created")?.let(Instant::ofEpochSecond),
                expiresOn = entry.string("expiration_date")?.let(::date),
            )
        }

        /** OpenRouter's routers report `-1`: the price depends on the model they pick. */
        private fun JsonObject.price(name: String): BigDecimal? = decimal(name)?.takeIf { it.signum() >= 0 }

        private fun date(text: String): LocalDate? =
            try {
                LocalDate.parse(text.take(10))
            } catch (_: DateTimeParseException) {
                null
            }
    }
}

/** The outcome of [OpenRouterCatalog.fetch]. */
public sealed interface CatalogResult {
    /** The list, in OpenRouter's order (newest first), and when it was fetched. */
    public data class Ok(val models: List<OpenRouterModel>, val fetchedAt: Instant) : CatalogResult {
        override fun toString(): String = "Ok(${models.size} models, fetchedAt=$fetchedAt)"
    }

    /** OpenRouter could not be reached, or did not answer within the timeout. */
    public data object Unreachable : CatalogResult

    /** OpenRouter answered with a status other than 200, for example 429 or 5xx. */
    public data class HttpError(val code: Int) : CatalogResult

    /** A 200 reply that was not a model list. */
    public data object Malformed : CatalogResult
}

/**
 * One model in OpenRouter's catalog.
 *
 * Prices are US dollars per token, as exact decimals; multiply by token counts, or use the
 * per-million views for display.
 *
 * @property id what to pass as `model`, for example `anthropic/claude-sonnet-4.6`.
 * @property name a display name, for example `Anthropic: Claude Sonnet 4.6`.
 * @property contextLength the context window in tokens, or null if not reported.
 * @property promptPricePerToken null when OpenRouter gives no fixed price: its routers (such as
 *   `openrouter/auto`) report `-1` because the price depends on the model they choose.
 * @property supportedParameters request parameters the model accepts, such as `tools`,
 *   `structured_outputs`, `response_format`, `reasoning`.
 * @property inputModalities for example `text`, `image`, `file`, `audio`, `video`.
 * @property outputModalities for example `text`, `image`, `audio`.
 * @property created when OpenRouter added the model.
 * @property expiresOn the day OpenRouter plans to retire the model, when announced. A saved
 *   choice with a date here should be replaced before then.
 */
public data class OpenRouterModel(
    val id: String,
    val name: String,
    val description: String,
    val contextLength: Int?,
    val promptPricePerToken: BigDecimal?,
    val completionPricePerToken: BigDecimal?,
    val supportedParameters: Set<String>,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val created: Instant?,
    val expiresOn: LocalDate? = null,
) {
    /** US dollars per million prompt tokens, the unit OpenRouter's site shows. */
    public val promptPricePerMillionTokens: BigDecimal? get() = promptPricePerToken?.perMillion()

    /** US dollars per million completion tokens. */
    public val completionPricePerMillionTokens: BigDecimal? get() = completionPricePerToken?.perMillion()

    /**
     * True for a `:free` variant, or when both prices are zero. Free models have lower rate
     * limits, and some providers of free models may use prompts for training.
     */
    public val isFree: Boolean
        get() = id.endsWith(":free") || (promptPricePerToken?.signum() == 0 && completionPricePerToken?.signum() == 0)

    /** The model accepts `tools` (function calling). */
    public val supportsTools: Boolean get() = "tools" in supportedParameters

    /**
     * The model accepts a strict JSON-schema `response_format`, which is what `Needs.structuredJson`
     * sends. OpenRouter marks this with `structured_outputs`. A model that lists only
     * `response_format` may support JSON mode but not a schema, so it does not count.
     */
    public val supportsJsonSchema: Boolean get() = "structured_outputs" in supportedParameters

    /** The model takes images as input. */
    public val supportsVision: Boolean get() = "image" in inputModalities

    /**
     * What the router should assume about this model. A model that does not report a context
     * length gets the library's default.
     */
    public fun capabilities(): Capabilities = Capabilities(
        text = outputModalities.isEmpty() || "text" in outputModalities,
        vision = supportsVision,
        jsonSchema = supportsJsonSchema,
        maxContext = contextLength ?: Capabilities().maxContext,
    )

    private fun BigDecimal.perMillion(): BigDecimal = movePointRight(6).stripTrailingZeros()
}

/** The model with this exact [id], or null. Null for a saved id means the model was retired or renamed. */
public fun Iterable<OpenRouterModel>.byId(id: String): OpenRouterModel? = firstOrNull { it.id == id }

/** Only the free models ([OpenRouterModel.isFree]). */
public fun Iterable<OpenRouterModel>.freeOnly(): List<OpenRouterModel> = filter { it.isFree }

/** Only models whose reported context window is at least [tokens]. Models that report none are left out. */
public fun Iterable<OpenRouterModel>.withMinContext(tokens: Int): List<OpenRouterModel> =
    filter { (it.contextLength ?: 0) >= tokens }

/** Only models that accept `tools`. */
public fun Iterable<OpenRouterModel>.supportingTools(): List<OpenRouterModel> = filter { it.supportsTools }

/** Only models that accept a strict JSON schema ([OpenRouterModel.supportsJsonSchema]). */
public fun Iterable<OpenRouterModel>.supportingJsonSchema(): List<OpenRouterModel> = filter { it.supportsJsonSchema }
