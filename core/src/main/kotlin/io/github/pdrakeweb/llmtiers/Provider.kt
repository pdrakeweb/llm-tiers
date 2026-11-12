package io.github.pdrakeweb.llmtiers

import kotlinx.coroutines.flow.Flow

/** One place a request can run. */
public interface Provider {
    public val id: ProviderId
    public val tier: Tier

    /** Which data sensitivities this provider may receive at all. */
    public val accepts: Set<Sensitivity>

    /**
     * Whether to run the cheap [probe] before sending a request. True by default for on-device
     * providers (the model may not be ready). Set it for a remote provider that is often
     * unreachable, such as a server on the home network when the phone is away: the request then
     * moves on in about a second instead of waiting for a timeout. A provider that is normally
     * up is simply tried: a fast failure beats pre-emptive avoidance.
     */
    public val probeBeforeUse: Boolean get() = tier == Tier.ON_DEVICE

    public suspend fun capabilities(): Capabilities

    /** Cheap health check. The executor bounds it with the probe timeout. */
    public suspend fun probe(): Reachability

    /**
     * Runs the request. The flow emits zero or more [ProviderEvent.Token]s and then exactly one of
     * [ProviderEvent.Done], [ProviderEvent.Escalate] or [ProviderEvent.Failed]. It must stop work
     * when its collector is cancelled.
     */
    public fun stream(request: PreparedRequest): Flow<ProviderEvent>
}

/** A provider's static description plus its capabilities, as the policy sees it. */
public data class ProviderInfo(
    val id: ProviderId,
    val tier: Tier,
    val accepts: Set<Sensitivity>,
    val capabilities: Capabilities,
    /** The app's own judgement of answer quality, 0 to 1. Used by quality ranking and by strategies that pick a "best". */
    val quality: Double = 0.5,
)

public sealed interface ProviderEvent {
    public data class Token(val text: String) : ProviderEvent {
        override fun toString(): String = "Token(${text.length} chars)"
    }
    public data class Done(val usage: Usage?) : ProviderEvent

    /**
     * "I am working on it, but have no answer text yet": a reasoning model that is thinking. It
     * carries no content. The executor counts it as a sign of life, so a provider that thinks for
     * longer than the first-token timeout is not mistaken for one that is silent.
     */
    public data object Thinking : ProviderEvent

    /** "I can't answer this; try the next tier." */
    public data object Escalate : ProviderEvent
    public data class Failed(val failure: ProviderFailure) : ProviderEvent
}

/** Every way a provider can fail. Exhaustive by design. */
public sealed interface ProviderFailure {
    public data object Unreachable : ProviderFailure
    public data object Timeout : ProviderFailure
    public data object FirstTokenTimeout : ProviderFailure
    public data object Stalled : ProviderFailure
    public data object AuthRejected : ProviderFailure
    public data object KeyMissing : ProviderFailure
    public data object RateLimited : ProviderFailure
    public data class ContextTooLong(val max: Int) : ProviderFailure
    public data class Server(val code: Int) : ProviderFailure
    public data object ModelNotReady : ProviderFailure
    public data object Busy : ProviderFailure
    public data object LocalNetworkPermissionDenied : ProviderFailure

    /** [detail] must never contain request or response content. */
    public data class Other(val detail: String) : ProviderFailure
}

/**
 * Whether this failure proves the provider did not process the request. Only then may a request
 * that is not marked safe to retry be sent to another provider.
 */
internal val ProviderFailure.provesNotProcessed: Boolean
    get() = when (this) {
        ProviderFailure.Unreachable, ProviderFailure.AuthRejected, ProviderFailure.KeyMissing,
        ProviderFailure.RateLimited, is ProviderFailure.ContextTooLong, ProviderFailure.ModelNotReady,
        ProviderFailure.Busy, ProviderFailure.LocalNetworkPermissionDenied -> true
        ProviderFailure.Timeout, ProviderFailure.FirstTokenTimeout, ProviderFailure.Stalled,
        is ProviderFailure.Server, is ProviderFailure.Other -> false
    }

/** Content-free name used in logs. */
internal val ProviderFailure.kind: String
    get() = when (this) {
        ProviderFailure.Unreachable -> "Unreachable"
        ProviderFailure.Timeout -> "Timeout"
        ProviderFailure.FirstTokenTimeout -> "FirstTokenTimeout"
        ProviderFailure.Stalled -> "Stalled"
        ProviderFailure.AuthRejected -> "AuthRejected"
        ProviderFailure.KeyMissing -> "KeyMissing"
        ProviderFailure.RateLimited -> "RateLimited"
        is ProviderFailure.ContextTooLong -> "ContextTooLong"
        is ProviderFailure.Server -> "Server($code)"
        ProviderFailure.ModelNotReady -> "ModelNotReady"
        ProviderFailure.Busy -> "Busy"
        ProviderFailure.LocalNetworkPermissionDenied -> "LocalNetworkPermissionDenied"
        is ProviderFailure.Other -> "Other"
    }
