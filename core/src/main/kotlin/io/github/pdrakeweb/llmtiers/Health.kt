package io.github.pdrakeweb.llmtiers

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `Ready → Caution` on a failure, `Out` after repeated failures (cooldown), `Probing` once the
 * cooldown ends, back to `Ready` on success.
 */
public enum class HealthState { Ready, Caution, Out, Probing }

public data class ProviderHealth(
    val state: HealthState = HealthState.Ready,
    /** Typical time to first token learned from this provider's history; null until known. */
    val typicalFirstToken: Duration? = null,
)

/** Health of every provider on one network, as the policy sees it. */
public data class HealthSnapshot(val providers: Map<ProviderId, ProviderHealth> = emptyMap()) {
    public operator fun get(id: ProviderId): ProviderHealth = providers[id] ?: ProviderHealth()
}

public data class HealthConfig(
    /** Failures within [window] that take a provider out of rotation. */
    val failureThreshold: Int = 3,
    val window: Duration = 60.seconds,
    val cooldown: Duration = 30.seconds,
    /** Cooldown doubles each time a probing provider fails again, up to this. */
    val maxCooldown: Duration = 10.minutes,
    /** Cooldown after `Busy` or `RateLimited`. */
    val shortCooldown: Duration = 10.seconds,
    /** How long a successful probe is trusted on the same network. */
    val probeTtl: Duration = 60.seconds,
)

public data class TimeoutConfig(
    /** Default first-token timeout, used until [learnAfter] samples exist. */
    val firstToken: Duration = 10.seconds,
    /** Default stall timeout between tokens. */
    val stall: Duration = 5.seconds,
    val probe: Duration = 1.seconds,
    /** Samples needed before a provider's timeouts are learned from its history. */
    val learnAfter: Int = 20,
    /** Learned timeout = this percentile of observed values, times [margin]. */
    val percentile: Double = 0.95,
    val margin: Double = 2.0,
    val min: Duration = 1.seconds,
    val max: Duration = 60.seconds,
    /** How long the approval callback may take before it counts as a refusal. */
    val approval: Duration = 2.minutes,
    /** Bound on the short-circuit hook and on `capabilities()`. */
    val hook: Duration = 5.seconds,
)

public data class AttemptTimeouts(val firstToken: Duration, val stall: Duration)

/** Tracks provider health per network, and learns each provider's timeouts. */
public class HealthTracker(
    private val clock: Clock,
    private val config: HealthConfig = HealthConfig(),
    private val timeouts: TimeoutConfig = TimeoutConfig(),
) {
    public suspend fun snapshot(network: NetworkId): HealthSnapshot = TODO("T1: health")

    public suspend fun state(provider: ProviderId, network: NetworkId): HealthState = TODO("T1: health")

    /** Records a completed answer, with its first-token latency and longest gap between tokens. */
    public suspend fun recordSuccess(provider: ProviderId, network: NetworkId, firstToken: Duration?, maxGap: Duration?): Unit =
        TODO("T1: health")

    public suspend fun recordFailure(provider: ProviderId, network: NetworkId, failure: ProviderFailure): Unit =
        TODO("T1: health")

    /** True when a probe succeeded on this network recently enough to skip probing again. */
    public suspend fun probeIsFresh(provider: ProviderId, network: NetworkId): Boolean = TODO("T1: health")

    public suspend fun recordProbeSuccess(provider: ProviderId, network: NetworkId): Unit = TODO("T1: health")

    public suspend fun timeoutsFor(provider: ProviderId): AttemptTimeouts = TODO("T1: health")

    /** Forgets everything about a provider, for example after the user fixes its key. */
    public suspend fun reset(provider: ProviderId): Unit = TODO("T1: health")
}
