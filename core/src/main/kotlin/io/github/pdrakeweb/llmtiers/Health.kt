package io.github.pdrakeweb.llmtiers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    private data class Key(val provider: ProviderId, val network: NetworkId)

    private class Entry {
        val failures = ArrayDeque<Long>()
        var outUntil: Long? = null
        var cooldown: Duration = Duration.ZERO
        var probedAt: Long? = null
    }

    private class Latency {
        val firstToken = ArrayDeque<Long>()
        val gap = ArrayDeque<Long>()
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, Entry>()
    private val latency = HashMap<ProviderId, Latency>()

    public suspend fun snapshot(network: NetworkId): HealthSnapshot = mutex.withLock {
        val now = clock.nowMillis()
        val ids = entries.keys.filter { it.network == network }.map { it.provider } + latency.keys
        HealthSnapshot(ids.distinct().associateWith { id ->
            ProviderHealth(
                state = entries[Key(id, network)]?.state(now) ?: HealthState.Ready,
                typicalFirstToken = latency[id]?.firstToken
                    ?.takeIf { it.size >= TYPICAL_MIN_SAMPLES }?.let { percentile(it, TYPICAL_PERCENTILE).milliseconds },
            )
        })
    }

    public suspend fun state(provider: ProviderId, network: NetworkId): HealthState = mutex.withLock {
        entries[Key(provider, network)]?.state(clock.nowMillis()) ?: HealthState.Ready
    }

    /** Records a completed answer, with its first-token latency and longest gap between tokens. */
    public suspend fun recordSuccess(provider: ProviderId, network: NetworkId, firstToken: Duration?, maxGap: Duration?): Unit =
        mutex.withLock {
            entries[Key(provider, network)]?.apply {
                failures.clear()
                outUntil = null
                cooldown = Duration.ZERO
            }
            val l = latency.getOrPut(provider) { Latency() }
            firstToken?.let { l.firstToken.addCapped(it.inWholeMilliseconds) }
            maxGap?.let { l.gap.addCapped(it.inWholeMilliseconds) }
        }

    public suspend fun recordFailure(provider: ProviderId, network: NetworkId, failure: ProviderFailure): Unit = mutex.withLock {
        // A prompt that is too long says something about the request, not about the provider.
        if (failure is ProviderFailure.ContextTooLong) return@withLock
        // A learned timeout that fires may simply be out of date (the provider got slower). Samples
        // come only from successes, so forget them and relearn from the defaults; otherwise a
        // too-tight timeout could never correct itself.
        when (failure) {
            ProviderFailure.FirstTokenTimeout -> latency[provider]?.firstToken?.clear()
            ProviderFailure.Stalled -> latency[provider]?.gap?.clear()
            else -> Unit
        }
        val now = clock.nowMillis()
        val e = entry(Key(provider, network))
        e.probedAt = null
        val until = e.outUntil
        when {
            // Failed while probing: out again, for twice as long.
            until != null && now >= until -> e.goOut(now, minOf(e.cooldown * 2, config.maxCooldown))
            until != null -> Unit // already out
            else -> {
                e.failures.addLast(now)
                e.prune(now)
                val immediate = immediateCooldown(failure)
                when {
                    immediate != null -> e.goOut(now, immediate)
                    e.failures.size >= config.failureThreshold -> e.goOut(now, config.cooldown)
                }
            }
        }
    }

    /** True when a probe succeeded on this network recently enough to skip probing again. */
    public suspend fun probeIsFresh(provider: ProviderId, network: NetworkId): Boolean = mutex.withLock {
        val at = entries[Key(provider, network)]?.probedAt ?: return@withLock false
        clock.nowMillis() - at < config.probeTtl.inWholeMilliseconds
    }

    public suspend fun recordProbeSuccess(provider: ProviderId, network: NetworkId): Unit = mutex.withLock {
        entry(Key(provider, network)).probedAt = clock.nowMillis()
    }

    /** Networks come and go (every reconnect can be a new id), so the oldest entries are dropped. */
    private fun entry(key: Key): Entry {
        entries[key]?.let { return it }
        if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        return Entry().also { entries[key] = it }
    }

    public suspend fun timeoutsFor(provider: ProviderId): AttemptTimeouts = mutex.withLock {
        val l = latency[provider]
        AttemptTimeouts(
            firstToken = learned(l?.firstToken, timeouts.firstToken),
            stall = learned(l?.gap, timeouts.stall),
        )
    }

    /** Forgets everything about a provider, for example after the user fixes its key. */
    public suspend fun reset(provider: ProviderId): Unit = mutex.withLock {
        entries.keys.removeAll { it.provider == provider }
        latency.remove(provider)
    }

    private fun learned(samples: ArrayDeque<Long>?, default: Duration): Duration {
        if (samples == null || samples.size < timeouts.learnAfter) return default
        val value = (percentile(samples, timeouts.percentile) * timeouts.margin).toLong().milliseconds
        return value.coerceIn(timeouts.min, timeouts.max)
    }

    /** Failures that say "not now" outright take the provider out without waiting for a pattern. */
    private fun immediateCooldown(failure: ProviderFailure): Duration? = when (failure) {
        ProviderFailure.Busy, ProviderFailure.RateLimited -> config.shortCooldown
        ProviderFailure.Unreachable, ProviderFailure.ModelNotReady, ProviderFailure.LocalNetworkPermissionDenied,
        ProviderFailure.AuthRejected, ProviderFailure.KeyMissing -> config.cooldown
        ProviderFailure.Timeout, ProviderFailure.FirstTokenTimeout, ProviderFailure.Stalled,
        is ProviderFailure.ContextTooLong, is ProviderFailure.Server, is ProviderFailure.Other -> null
    }

    private fun Entry.goOut(now: Long, duration: Duration) {
        cooldown = duration
        outUntil = now + duration.inWholeMilliseconds
        failures.clear()
    }

    private fun Entry.prune(now: Long) {
        val oldest = now - config.window.inWholeMilliseconds
        while (failures.isNotEmpty() && failures.first() <= oldest) failures.removeFirst()
    }

    private fun Entry.state(now: Long): HealthState {
        val until = outUntil
        if (until != null) return if (now < until) HealthState.Out else HealthState.Probing
        prune(now)
        return if (failures.isEmpty()) HealthState.Ready else HealthState.Caution
    }

    private fun ArrayDeque<Long>.addCapped(value: Long) {
        addLast(value)
        if (size > MAX_SAMPLES) removeFirst()
    }

    /** Nearest-rank percentile. */
    private fun percentile(samples: Collection<Long>, p: Double): Long {
        val sorted = samples.sorted()
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private companion object {
        const val MAX_SAMPLES = 100
        const val MAX_ENTRIES = 256
        const val TYPICAL_MIN_SAMPLES = 5
        const val TYPICAL_PERCENTILE = 0.9
    }
}
