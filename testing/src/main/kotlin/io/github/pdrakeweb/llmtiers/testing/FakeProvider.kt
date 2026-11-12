package io.github.pdrakeweb.llmtiers.testing

import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.Provider
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.Usage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/** What a [FakeProvider] does for one call. Built with the functions below, run in order. */
public class Script {
    internal sealed interface Op {
        data class Wait(val duration: Duration) : Op
        data class Emit(val event: ProviderEvent) : Op
        data object Hang : Op
        data class Crash(val exception: Throwable) : Op
    }

    internal val ops = mutableListOf<Op>()

    public fun wait(duration: Duration) { ops += Op.Wait(duration) }
    public fun token(text: String) { ops += Op.Emit(ProviderEvent.Token(text)) }
    public fun tokens(vararg texts: String) { texts.forEach(::token) }
    public fun done(usage: Usage? = null) { ops += Op.Emit(ProviderEvent.Done(usage)) }
    public fun fail(failure: ProviderFailure) { ops += Op.Emit(ProviderEvent.Failed(failure)) }
    public fun escalate() { ops += Op.Emit(ProviderEvent.Escalate) }

    /** Never emits again; only cancellation ends the call. */
    public fun hang() { ops += Op.Hang }

    /** The provider's flow throws. */
    public fun crash(exception: Throwable = IllegalStateException("scripted crash")) { ops += Op.Crash(exception) }
}

/**
 * A provider with scripted latency, tokens, stalls and failures. It records every
 * [PreparedRequest] it receives so tests can assert exactly what would have left the device.
 *
 * Under `runTest`, scripted waits use virtual time.
 */
public class FakeProvider(
    id: String,
    override val tier: Tier,
    override val accepts: Set<Sensitivity> = setOf(Sensitivity.Public, Sensitivity.Personal),
    public var caps: Capabilities = Capabilities(),
    default: Script.() -> Unit = { token("ok"); done() },
) : Provider {
    override val id: ProviderId = ProviderId(id)

    private var defaultScript: Script = Script().apply(default)
    private val queued = ArrayDeque<Script>()
    private val _received = mutableListOf<PreparedRequest>()

    /** Every request this provider was asked to run, in order. */
    public val received: List<PreparedRequest> get() = _received.toList()
    public val calls: Int get() = _received.size
    public var probes: Int = 0
        private set

    /** What `probe()` does. Defaults to reachable. */
    public var onProbe: suspend () -> Reachability = { Reachability.Reachable }

    /** What `capabilities()` does. Defaults to returning [caps]. */
    public var onCapabilities: suspend () -> Capabilities = { caps }

    /** Replaces the behaviour for every call that has no queued script. */
    public fun always(script: Script.() -> Unit): FakeProvider = apply { defaultScript = Script().apply(script) }

    /** Queues behaviour for the next call only. */
    public fun next(script: Script.() -> Unit): FakeProvider = apply { queued += Script().apply(script) }

    public fun probeUnreachable(failure: ProviderFailure = ProviderFailure.Unreachable): FakeProvider =
        apply { onProbe = { Reachability.Unreachable(failure) } }

    public fun probeHangs(): FakeProvider = apply { onProbe = { awaitCancellation() } }

    public fun probeReachable(): FakeProvider = apply { onProbe = { Reachability.Reachable } }

    override suspend fun capabilities(): Capabilities = onCapabilities()

    override suspend fun probe(): Reachability {
        probes++
        return onProbe()
    }

    override fun stream(request: PreparedRequest): Flow<ProviderEvent> = flow {
        _received += request
        val script = queued.removeFirstOrNull() ?: defaultScript
        for (op in script.ops) {
            when (op) {
                is Script.Op.Wait -> delay(op.duration)
                is Script.Op.Emit -> emit(op.event)
                Script.Op.Hang -> awaitCancellation()
                is Script.Op.Crash -> throw op.exception
            }
        }
    }
}
