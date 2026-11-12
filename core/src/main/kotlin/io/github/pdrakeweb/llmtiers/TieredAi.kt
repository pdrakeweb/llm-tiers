package io.github.pdrakeweb.llmtiers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Asks the app whether this request's `Personal` items may go to this provider. */
public typealias Approve = suspend (request: Request, provider: ProviderInfo) -> Boolean

/** Runs before any routing. A non-null return answers the request; no model is called. */
public typealias ShortCircuit = suspend (request: Request) -> Any?

public class TieredAiBuilder internal constructor() {
    internal val providers = mutableListOf<Provider>()
    internal var order: List<ProviderId>? = null
    internal var approve: Approve? = null
    internal var shortCircuit: ShortCircuit? = null

    public var clock: Clock = Clock.System
    public var network: NetworkMonitor = NetworkMonitor.fixed()
    public var timeouts: TimeoutConfig = TimeoutConfig()
    public var health: HealthConfig = HealthConfig()
    public var logger: Logger? = null

    public fun provider(provider: Provider) {
        require(providers.none { it.id == provider.id }) { "duplicate provider id ${provider.id}" }
        providers += provider
    }

    /** The app's preferred order. Providers not named keep their registration order, after these. */
    public fun order(vararg ids: String) {
        order = ids.map(::ProviderId)
    }

    /** Without this, a request with `Personal` items never reaches a LAN or cloud provider. */
    public fun approve(block: Approve) {
        approve = block
    }

    public fun shortCircuit(block: ShortCircuit) {
        shortCircuit = block
    }
}

/** Builds the single call site: `ai.generate(request)` or `ai.stream(request)`. */
public fun TieredAi(block: TieredAiBuilder.() -> Unit): TieredAi = TieredAi(TieredAiBuilder().apply(block))

public class TieredAi internal constructor(builder: TieredAiBuilder) {
    private val providers: List<Provider> = run {
        val named = builder.order.orEmpty().map { id ->
            builder.providers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("order() names unknown provider $id")
        }
        (named + builder.providers).distinctBy { it.id }
    }
    private val approve = builder.approve
    private val shortCircuit = builder.shortCircuit
    private val clock = builder.clock
    private val network = builder.network
    private val timeouts = builder.timeouts
    private val logger = builder.logger

    /** Provider health and learned timeouts. Exposed so an app can show or reset it. */
    public val health: HealthTracker = HealthTracker(clock, builder.health, timeouts)

    /**
     * Runs the request and streams what happens: `Started`, `Token`s, `FellBack` when the request
     * moves to another provider, then exactly one `Completed`.
     */
    public fun stream(request: Request): Flow<Event> = flow {
        val attempts = mutableListOf<Attempt>()
        val flight = Flight()
        val deadline = request.deadline
        val result = if (deadline == null) {
            execute(request, attempts, flight)
        } else {
            withTimeoutOrNull(deadline) { execute(request, attempts, flight) } ?: run {
                flight.info?.let { p ->
                    attempts.record(
                        Attempt(
                            p.id, p.tier, Outcome.Failed(ProviderFailure.Timeout), flight.why,
                            elapsed = (clock.nowMillis() - flight.startedAt).milliseconds,
                        ),
                        "deadline",
                    )
                }
                Result.NoVerdict(attempts.toList())
            }
        }
        log(
            LogRecord(
                event = "completed",
                provider = (result as? Result.Answer)?.answeredBy,
                tier = (result as? Result.Answer)?.tier,
                outcome = when (result) {
                    is Result.Answer -> if (result.incomplete == null) "Answer" else "Answer(incomplete:${result.incomplete.kind})"
                    is Result.Answered -> "Answered"
                    is Result.Blocked -> "Blocked"
                    is Result.NoVerdict -> "NoVerdict"
                    is Result.NoneAvailable -> "NoneAvailable"
                },
            )
        )
        emit(Event.Completed(result))
    }

    /** Runs the request and returns how it ended. Never throws for provider failures. */
    public suspend fun generate(request: Request): Result =
        stream(request).filterIsInstance<Event.Completed>().first().result

    private class Flight {
        var info: ProviderInfo? = null
        var why: String = ""
        var startedAt: Long = 0
    }

    private class Pending(val from: ProviderId, val reason: Outcome)

    private suspend fun FlowCollector<Event>.execute(original: Request, attempts: MutableList<Attempt>, flight: Flight): Result {
        // 0. Short-circuit. Fails closed: if the hook cannot give a clear "not mine", no model runs.
        if (shortCircuit != null) {
            val verdict = guarded(timeouts.hook) { ShortCircuitVerdict(shortCircuit.invoke(original)) }
                ?: return Result.Blocked("The short-circuit hook failed or did not finish in time, so no model was called.")
            verdict.answer?.let { return Result.Answered(it) }
        }

        // 1. Privacy. Identifier items are removed here; nothing after this line can see them.
        val request = original.copy(data = original.data.filter { it.sensitivity != Sensitivity.Identifier })
        val net = try {
            network.state.value
        } catch (e: Exception) {
            NetworkState.Unknown // a broken monitor must not break the request
        }

        val infos = coroutineScope {
            providers.map { p ->
                async { guarded(timeouts.hook) { p.capabilities() }?.let { ProviderInfo(p.id, p.tier, p.accepts, it) } }
            }.awaitAll()
        }
        providers.forEachIndexed { i, p ->
            if (infos[i] == null) attempts.skip(p.id, p.tier, SkipReason.CapabilitiesUnavailable, "")
        }

        val plan = plan(request, infos.filterNotNull(), health.snapshot(net.id), net)
        plan.skipped.forEach { attempts.skip(it.provider, it.tier, it.reason, "") }

        var pending: Pending? = null
        var staleTokens = false // an escalated provider's tokens are still on screen
        var discarded = false // ... so the final answer must replace them, not append
        var contextFloor = 0
        var replayBlocked = false

        for (step in plan.steps) {
            val info = step.provider
            val provider = providers.first { it.id == info.id }

            if (replayBlocked) {
                attempts.skip(info.id, info.tier, SkipReason.NotSafeToRetry, step.reason)
                continue
            }
            if (contextFloor > 0 && info.capabilities.maxContext <= contextFloor) {
                attempts.skip(info.id, info.tier, SkipReason.MissingCapability("context"), step.reason)
                continue
            }
            // Approval comes before anything touches the provider, the probe included.
            if (step.needsApproval) {
                val approved = approve != null && guarded(timeouts.approval) { approve.invoke(request, info) } == true
                if (!approved) {
                    attempts.skip(info.id, info.tier, SkipReason.ApprovalDenied, step.reason)
                    continue
                }
            }

            val startedAt = clock.nowMillis()
            fun elapsed() = (clock.nowMillis() - startedAt).milliseconds

            // A LAN miss is the common case away from home, and an on-device model may not be
            // ready, so both are probed cheaply first. Cloud is simply tried: a fast failure beats
            // pre-emptive avoidance.
            if (info.tier != Tier.CLOUD && !health.probeIsFresh(info.id, net.id)) {
                val reach = guarded(timeouts.probe) { provider.probe() } ?: Reachability.Unreachable(ProviderFailure.Unreachable)
                if (reach is Reachability.Unreachable) {
                    health.recordFailure(info.id, net.id, reach.failure)
                    attempts.record(Attempt(info.id, info.tier, Outcome.Failed(reach.failure), step.reason, elapsed()), "probe")
                    pending = Pending(info.id, Outcome.Failed(reach.failure))
                    continue
                }
                health.recordProbeSuccess(info.id, net.id)
            }

            val prepared = PreparedRequest(request.messages, request.data, request.needs, request.task, request.idempotencyKey)
            pending?.let {
                emit(Event.FellBack(it.from, info.id, it.reason, discardPartial = staleTokens))
                staleTokens = false
            }
            emit(Event.Started(info.id, info.tier))
            flight.info = info
            flight.why = step.reason
            flight.startedAt = startedAt
            val end = runAttempt(provider, prepared, health.timeoutsFor(info.id))
            flight.info = null

            val base = Attempt(
                info.id, info.tier, Outcome.Succeeded, step.reason, elapsed(),
                firstTokenAfter = end.firstTokenAt?.let { (it - startedAt).milliseconds }, usage = end.usage,
            )
            when (end.kind) {
                EndKind.Done -> {
                    health.recordSuccess(
                        info.id, net.id, base.firstTokenAfter, end.maxGap?.milliseconds,
                        firstSignal = end.firstSignalAt?.let { (it - startedAt).milliseconds },
                    )
                    attempts.record(base, "attempt")
                    return Result.Answer(end.text, info.id, info.tier, attempts.toList(), discardPartial = discarded)
                }
                EndKind.Escalated -> {
                    attempts.record(base.copy(outcome = Outcome.Escalated), "attempt")
                    if (end.firstTokenAt != null) {
                        staleTokens = true
                        discarded = true
                    }
                    pending = Pending(info.id, Outcome.Escalated)
                }
                EndKind.Failed -> {
                    val failure = end.failure ?: ProviderFailure.Other("unknown")
                    health.recordFailure(info.id, net.id, failure)
                    attempts.record(base.copy(outcome = Outcome.Failed(failure)), "attempt")
                    // First-token commit: once a token has streamed the request stays with this provider.
                    if (end.firstTokenAt != null) {
                        return Result.Answer(
                            end.text, info.id, info.tier, attempts.toList(), discardPartial = discarded, incomplete = failure,
                        )
                    }
                    if (failure is ProviderFailure.ContextTooLong) {
                        contextFloor = maxOf(contextFloor, if (failure.max > 0) failure.max else info.capabilities.maxContext)
                    }
                    if (!failure.provesNotProcessed && !request.replayable) replayBlocked = true
                    pending = Pending(info.id, Outcome.Failed(failure))
                }
            }
        }

        // Nothing answered. If no provider was even tried and privacy excluded at least one, the
        // honest report is that privacy blocked the request.
        val tried = attempts.any { it.outcome !is Outcome.Skipped }
        val privacy = attempts.any { (it.outcome as? Outcome.Skipped)?.reason?.privacy == true }
        if (!tried && privacy) {
            val detail = attempts.joinToString("; ") { "${it.provider} (${it.tier}): ${(it.outcome as Outcome.Skipped).reason.code}" }
            return Result.Blocked("Privacy rules left no provider this request may be sent to. $detail")
        }
        return Result.NoneAvailable(attempts.toList())
    }

    private enum class EndKind { Done, Escalated, Failed }

    private class End(
        val kind: EndKind,
        val text: String,
        val firstTokenAt: Long?,
        /** When the provider first showed life: its first `Thinking` or its first token. */
        val firstSignalAt: Long?,
        val maxGap: Long?,
        val failure: ProviderFailure? = null,
        val usage: Usage? = null,
    )

    /**
     * One attempt, bounded by the first-token and stall timeouts.
     *
     * Before any sign of life the provider has the first-token timeout. Once it reports
     * `Thinking` it is alive: it must keep showing life within the stall timeout, and must produce
     * its first token within the thinking allowance. After the first token only the stall timeout
     * applies, and both tokens and `Thinking` count as life.
     *
     * The provider is collected in a job that is cancelled but deliberately not joined: a provider
     * that ignores cancellation must not be able to hang the request (invariant A-2).
     */
    private suspend fun FlowCollector<Event>.runAttempt(provider: Provider, prepared: PreparedRequest, limits: AttemptTimeouts): End {
        val channel = Channel<ProviderEvent>(Channel.RENDEZVOUS)
        val job = CoroutineScope(currentCoroutineContext().minusKey(Job)).launch {
            try {
                provider.stream(prepared).collect { channel.send(it) }
            } catch (e: Throwable) {
                ensureActive()
                // Only the exception type: its message may carry request or response content.
                channel.send(ProviderEvent.Failed(ProviderFailure.Other(e::class.simpleName ?: "exception")))
            } finally {
                channel.close()
            }
        }
        val text = StringBuilder()
        val startedAt = clock.nowMillis()
        var firstAt: Long? = null
        var firstSignalAt: Long? = null
        var announcedThinking = false
        var lastAt = 0L
        var maxGap: Long? = null
        fun end(kind: EndKind, failure: ProviderFailure? = null, usage: Usage? = null) =
            End(kind, text.toString(), firstAt, firstSignalAt, maxGap, failure, usage)
        try {
            while (true) {
                val wait = when {
                    firstAt != null -> limits.stall
                    firstSignalAt != null -> {
                        val allowance = timeouts.thinking - (clock.nowMillis() - startedAt).milliseconds
                        if (allowance <= Duration.ZERO) return end(EndKind.Failed, ProviderFailure.FirstTokenTimeout)
                        minOf(limits.stall, allowance)
                    }
                    else -> limits.firstToken
                }
                val received = withTimeoutOrNull(wait) { channel.receiveCatching() }
                    ?: return end(EndKind.Failed, if (firstAt == null) ProviderFailure.FirstTokenTimeout else ProviderFailure.Stalled)
                val event = received.getOrNull()
                    ?: return end(EndKind.Failed, ProviderFailure.Other("stream ended without Done"))
                val now = clock.nowMillis()
                when (event) {
                    is ProviderEvent.Token -> {
                        if (firstSignalAt == null) firstSignalAt = now
                        if (firstAt == null) firstAt = now else maxGap = maxOf(maxGap ?: 0, now - lastAt)
                        lastAt = now
                        text.append(event.text)
                        emit(Event.Token(event.text))
                    }
                    is ProviderEvent.Done -> {
                        if (firstAt != null) maxGap = maxOf(maxGap ?: 0, now - lastAt)
                        return end(EndKind.Done, usage = event.usage)
                    }
                    ProviderEvent.Thinking -> {
                        if (firstSignalAt == null) firstSignalAt = now
                        // Between tokens it is a sign of life like any other, so it counts for the stall gap.
                        if (firstAt != null) {
                            maxGap = maxOf(maxGap ?: 0, now - lastAt)
                            lastAt = now
                        }
                        if (!announcedThinking) {
                            announcedThinking = true
                            emit(Event.Thinking(provider.id))
                        }
                    }
                    ProviderEvent.Escalate -> return end(EndKind.Escalated)
                    is ProviderEvent.Failed -> return end(EndKind.Failed, event.failure)
                }
            }
        } finally {
            job.cancel()
        }
    }

    private class ShortCircuitVerdict(val answer: Any?)

    /** Runs app or provider code so that an exception or a hang becomes null instead of spreading. */
    /**
     * Like [runAttempt], the block runs in a job that is cancelled but not joined, so a callback
     * that ignores cancellation (a `suspendCoroutine` that never resumes) cannot hang the request.
     */
    private suspend fun <T : Any> guarded(timeout: Duration, block: suspend () -> T): T? {
        val outcome = CompletableDeferred<T?>()
        val job = CoroutineScope(currentCoroutineContext().minusKey(Job)).launch {
            outcome.complete(
                try {
                    block()
                } catch (e: Throwable) {
                    null
                }
            )
        }
        return try {
            withTimeoutOrNull(timeout) { outcome.await() }
        } finally {
            job.cancel()
        }
    }

    private fun MutableList<Attempt>.skip(provider: ProviderId, tier: Tier, reason: SkipReason, why: String) {
        record(Attempt(provider, tier, Outcome.Skipped(reason), why), "skipped")
    }

    private fun MutableList<Attempt>.record(attempt: Attempt, event: String) {
        add(attempt)
        log(
            LogRecord(
                event = event,
                provider = attempt.provider,
                tier = attempt.tier,
                outcome = when (val o = attempt.outcome) {
                    Outcome.Succeeded -> "Succeeded"
                    Outcome.Escalated -> "Escalated"
                    is Outcome.Failed -> o.failure.kind
                    is Outcome.Skipped -> o.reason.code
                },
                elapsedMillis = attempt.elapsed.inWholeMilliseconds,
                firstTokenMillis = attempt.firstTokenAfter?.inWholeMilliseconds,
            )
        )
    }

    /** Logs carry provider ids, timings and failure types only. A broken logger never breaks a request. */
    private fun log(record: LogRecord) {
        try {
            logger?.log(record)
        } catch (_: Throwable) {
        }
    }
}
