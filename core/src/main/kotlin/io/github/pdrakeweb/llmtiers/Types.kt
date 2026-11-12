package io.github.pdrakeweb.llmtiers

/** Stable identifier of a provider, chosen by the app (for example `"home-llama"`). */
@JvmInline
public value class ProviderId(public val value: String) {
    override fun toString(): String = value
}

/** Where a provider runs. */
public enum class Tier { ON_DEVICE, LAN, CLOUD }

/**
 * How sensitive a data item is. An app maps its own data classes onto these.
 *
 * - [Public]: may be sent to any provider that accepts it.
 * - [Personal]: reaches a LAN or cloud provider only after the app approves that request for that
 *   provider.
 * - [Identifier]: never leaves the device. It is removed before planning; no API sends one.
 */
public enum class Sensitivity { Public, Personal, Identifier }

/** What a provider can do. */
public data class Capabilities(
    val text: Boolean = true,
    val vision: Boolean = false,
    val jsonSchema: Boolean = false,
    /** Context length in tokens. */
    val maxContext: Int = 4096,
)

/** Result of a provider's cheap health check. */
public sealed interface Reachability {
    public data object Reachable : Reachability
    public data class Unreachable(val failure: ProviderFailure) : Reachability
}

/** Token counts reported by a provider, when it reports them. */
public data class Usage(val promptTokens: Int?, val completionTokens: Int?)

/** Monotonic time source. Tests supply a fake; see the `:testing` module. */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        /** Wall-clock-independent monotonic clock. */
        public val System: Clock = object : Clock {
            private val origin = kotlin.time.TimeSource.Monotonic.markNow()
            override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
        }
    }
}
