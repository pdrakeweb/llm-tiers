package io.github.pdrakeweb.llmtiers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    internal val qualities = HashMap<ProviderId, Double>()
    internal var order: List<ProviderId>? = null
    internal var approve: Approve? = null
    internal var shortCircuit: ShortCircuit? = null

    public var clock: Clock = Clock.System
    public var network: NetworkMonitor = NetworkMonitor.fixed()
    public var timeouts: TimeoutConfig = TimeoutConfig()
    public var health: HealthConfig = HealthConfig()
    public var logger: Logger? = null

    /**
     * @param quality the app's own judgement of this provider's answers, 0 to 1. It orders
     *   providers under [Ranking.Quality] and picks the "best" for [Strategy.FastThenBest] and
     *   [Strategy.BestOf]. It has no effect otherwise.
     */
    public fun provider(provider: Provider, quality: Double = 0.5) {
        require(providers.none { it.id == provider.id }) { "duplicate provider id ${provider.id}" }
        require(quality in 0.0..1.0) { "quality must be between 0 and 1" }
        providers += provider
        qualities[provider.id] = quality
    }

    /** The app's preferred order. Providers not named keep their registration order, after these. */
    public fun order(vararg ids: String) {
        order = ids.map(::ProviderId)
    }

    /** Without this, a request with `Personal` items never reaches a remote provider. */
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
    private val qualities: Map<ProviderId, Double> = builder.qualities.toMap()
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

    /** Everything one request carries from step to step. */
    private inner class Run(
        val request: Request,
        val net: NetworkState,
        val steps: List<Step>,
        val attempts: MutableList<Attempt>,
        val flight: Flight,
    ) {
        var pending: Pending? = null
        var staleTokens = false // tokens from a provider that was then dropped are still on screen
        var discarded = false // ... so the final answer must replace them, not append
        var contextFloor = 0
        var replayBlocked = false
        val used = HashSet<Int>() // steps already admitted or skipped by a concurrent strategy
        val prepared = PreparedRequest(request.messages, request.data, request.needs, request.task, request.idempotencyKey)
        fun provider(step: Step): Provider = providers.first { it.id == step.provider.id }
    }

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
                async {
                    guarded(timeouts.hook) { p.capabilities() }?.let { ProviderInfo(p.id, p.tier, p.accepts, it, qualities[p.id] ?: 0.5) }
                }
            }.awaitAll()
        }
        providers.forEachIndexed { i, p ->
            if (infos[i] == null) attempts.skip(p.id, p.tier, SkipReason.CapabilitiesUnavailable, "")
        }

        val plan = plan(request, infos.filterNotNull(), health.snapshot(net.id), net)
        plan.skipped.forEach { attempts.skip(it.provider, it.tier, it.reason, "") }
        val run = Run(request, net, plan.steps, attempts, flight)

        // Every strategy but Sequential sends the request to more than one provider at once, so
        // it needs the request to be safe to send twice, and at least two providers to choose from.
        val strategy = if (request.replayable && plan.steps.size > 1) request.strategy else Strategy.Sequential
        if (strategy != request.strategy) log(LogRecord(event = "strategy", outcome = "Sequential"))
        val early = when (strategy) {
            Strategy.Sequential -> null
            is Strategy.Race -> hedged(run, Duration.ZERO, strategy.maxParallel)
            is Strategy.Hedged -> hedged(run, strategy.after, strategy.maxParallel)
            Strategy.FastThenBest -> fastThenBest(run)
            is Strategy.BestOf -> bestOf(run, strategy)
        }
        // Whatever a concurrent strategy left untried is tried in order.
        (early ?: sequential(run))?.let { return it }

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

    /**
     * The checks every provider passes before it is sent anything: retry safety, context length,
     * the app's approval, then a probe. Returns the time the attempt started, or null when the
     * step was skipped or failed its probe (already recorded).
     */
    private suspend fun admit(run: Run, index: Int): Long? {
        val step = run.steps[index]
        val info = step.provider
        run.used += index
        if (run.replayBlocked) {
            run.attempts.skip(info.id, info.tier, SkipReason.NotSafeToRetry, step.reason)
            return null
        }
        if (run.contextFloor > 0 && info.capabilities.maxContext <= run.contextFloor) {
            run.attempts.skip(info.id, info.tier, SkipReason.MissingCapability("context"), step.reason)
            return null
        }
        // Approval comes before anything touches the provider, the probe included.
        if (step.needsApproval) {
            val approved = approve != null && guarded(timeouts.approval) { approve.invoke(run.request, info) } == true
            if (!approved) {
                run.attempts.skip(info.id, info.tier, SkipReason.ApprovalDenied, step.reason)
                return null
            }
        }
        val startedAt = clock.nowMillis()
        // A provider that asks for it is probed cheaply first: an on-device model may not be
        // ready, and a home server is usually unreachable away from home. Others are simply
        // tried: a fast failure beats pre-emptive avoidance.
        val provider = run.provider(step)
        if (provider.probeBeforeUse && !health.probeIsFresh(info.id, run.net.id)) {
            val reach = guarded(timeouts.probe) { provider.probe() } ?: Reachability.Unreachable(ProviderFailure.Unreachable)
            if (reach is Reachability.Unreachable) {
                health.recordFailure(info.id, run.net.id, reach.failure)
                run.attempts.record(
                    Attempt(info.id, info.tier, Outcome.Failed(reach.failure), step.reason, (clock.nowMillis() - startedAt).milliseconds),
                    "probe",
                )
                run.pending = Pending(info.id, Outcome.Failed(reach.failure))
                return null
            }
            health.recordProbeSuccess(info.id, run.net.id)
        }
        return startedAt
    }

    /** Records how an attempt ended, in health and in the attempt list, and returns the outcome. */
    private suspend fun settle(run: Run, index: Int, startedAt: Long, end: End): Outcome {
        val step = run.steps[index]
        val info = step.provider
        val firstToken = end.firstTokenAt?.let { (it - startedAt).milliseconds }
        val outcome = when (end.kind) {
            EndKind.Done -> {
                health.recordSuccess(
                    info.id, run.net.id, firstToken, end.maxGap?.milliseconds,
                    firstSignal = end.firstSignalAt?.let { (it - startedAt).milliseconds },
                )
                Outcome.Succeeded
            }
            EndKind.Escalated -> Outcome.Escalated
            EndKind.Failed -> {
                val failure = end.failure ?: ProviderFailure.Other("unknown")
                health.recordFailure(info.id, run.net.id, failure)
                if (end.firstTokenAt == null) {
                    if (failure is ProviderFailure.ContextTooLong) {
                        run.contextFloor = maxOf(run.contextFloor, if (failure.max > 0) failure.max else info.capabilities.maxContext)
                    }
                    if (!failure.provesNotProcessed && !run.request.replayable) run.replayBlocked = true
                }
                Outcome.Failed(failure)
            }
        }
        run.attempts.record(
            Attempt(info.id, info.tier, outcome, step.reason, (clock.nowMillis() - startedAt).milliseconds, firstToken, end.usage),
            "attempt",
        )
        return outcome
    }

    private fun Run.answer(index: Int, text: String, incomplete: ProviderFailure? = null): Result.Answer {
        val info = steps[index].provider
        return Result.Answer(text, info.id, info.tier, attempts.toList(), discardPartial = discarded, incomplete = incomplete)
    }

    /** One provider at a time. Skips steps a concurrent strategy already used. Null when nothing answered. */
    private suspend fun FlowCollector<Event>.sequential(run: Run): Result? {
        for (index in run.steps.indices) {
            if (index in run.used) continue
            val step = run.steps[index]
            val info = step.provider
            val startedAt = admit(run, index) ?: continue

            run.pending?.let {
                emit(Event.FellBack(it.from, info.id, it.reason, discardPartial = run.staleTokens))
                run.staleTokens = false
            }
            emit(Event.Started(info.id, info.tier))
            run.flight.info = info
            run.flight.why = step.reason
            run.flight.startedAt = startedAt
            var announced = false
            val end = runAttempt(
                run.provider(step), run.prepared, health.timeoutsFor(info.id),
                onToken = { emit(Event.Token(it)) },
                onThinking = { if (!announced) { announced = true; emit(Event.Thinking(info.id)) } },
            )
            run.flight.info = null

            when (val outcome = settle(run, index, startedAt, end)) {
                Outcome.Succeeded -> return run.answer(index, end.text)
                Outcome.Escalated -> {
                    if (end.firstTokenAt != null) {
                        run.staleTokens = true
                        run.discarded = true
                    }
                    run.pending = Pending(info.id, outcome)
                }
                is Outcome.Failed -> {
                    // First-token commit: once a token has streamed the request stays with this provider.
                    if (end.firstTokenAt != null) return run.answer(index, end.text, incomplete = outcome.failure)
                    run.pending = Pending(info.id, outcome)
                }
                else -> Unit
            }
        }
        return null
    }

    // ---- concurrent strategies ----------------------------------------------------------------

    private sealed interface Msg {
        class Token(val index: Int, val text: String) : Msg
        class Thinking(val index: Int) : Msg
        class Ended(val index: Int, val end: End) : Msg
        data object Tick : Msg
    }

    /** Several attempts running at once, reporting into one inbox that the collector drains. */
    private inner class Arena(private val scope: CoroutineScope, private val run: Run) {
        val inbox = Channel<Msg>(Channel.UNLIMITED)
        val jobs = HashMap<Int, Job>()
        val startedAt = HashMap<Int, Long>()
        private val thought = HashSet<Int>()

        suspend fun FlowCollector<Event>.start(index: Int, started: Long) {
            val step = run.steps[index]
            startedAt[index] = started
            if (run.flight.info == null) {
                run.flight.info = step.provider
                run.flight.why = step.reason
                run.flight.startedAt = started
            }
            emit(Event.Started(step.provider.id, step.provider.tier))
            val limits = health.timeoutsFor(step.provider.id)
            jobs[index] = scope.launch {
                val end = runAttempt(
                    run.provider(step), run.prepared, limits,
                    onToken = { inbox.send(Msg.Token(index, it)) },
                    onThinking = { inbox.send(Msg.Thinking(index)) },
                )
                inbox.send(Msg.Ended(index, end))
            }
        }

        suspend fun FlowCollector<Event>.thinking(index: Int) {
            if (thought.add(index)) emit(Event.Thinking(run.steps[index].provider.id))
        }

        /** Stops a provider that was started but will not be used, and says so in the attempts. */
        fun supersede(index: Int) {
            val job = jobs.remove(index) ?: return
            job.cancel()
            val step = run.steps[index]
            run.attempts.record(
                Attempt(
                    step.provider.id, step.provider.tier, Outcome.Superseded, step.reason,
                    (clock.nowMillis() - (startedAt[index] ?: clock.nowMillis())).milliseconds,
                ),
                "attempt",
            )
        }

        fun live(index: Int): Boolean = index in jobs
    }

    /** Admits and starts the next unused step, if any. */
    private suspend fun FlowCollector<Event>.startNext(run: Run, arena: Arena): Int? {
        for (index in run.steps.indices) {
            if (index in run.used) continue
            val started = admit(run, index) ?: continue
            with(arena) { start(index, started) }
            return index
        }
        return null
    }

    /**
     * Race and Hedged: providers run side by side and the first to produce answer text wins. With
     * [after] zero they all start together; otherwise each further one starts when that long has
     * passed without answer text, or at once when a running one fails.
     */
    private suspend fun FlowCollector<Event>.hedged(run: Run, after: Duration, maxParallel: Int): Result? = coroutineScope {
        val arena = Arena(this, run)
        val limit = maxParallel.coerceAtLeast(1)
        var winner: Int? = null
        var result: Result? = null
        var timer: Job? = null
        fun schedule() {
            timer?.cancel()
            timer = if (after > Duration.ZERO) launch { delay(after); arena.inbox.send(Msg.Tick) } else null
        }
        try {
            if (after > Duration.ZERO) {
                startNext(run, arena)
                schedule()
            } else {
                while (arena.jobs.size < limit && startNext(run, arena) != null) { }
            }
            val text = StringBuilder()
            while (arena.jobs.isNotEmpty()) {
                when (val msg = arena.inbox.receive()) {
                    Msg.Tick -> if (winner == null && arena.jobs.size < limit && startNext(run, arena) != null) schedule()
                    is Msg.Thinking -> if (arena.live(msg.index)) with(arena) { thinking(msg.index) }
                    is Msg.Token -> if (arena.live(msg.index) && (winner == null || winner == msg.index)) {
                        if (winner == null) {
                            winner = msg.index
                            timer?.cancel()
                            arena.jobs.keys.filter { it != msg.index }.forEach(arena::supersede)
                        }
                        text.append(msg.text)
                        emit(Event.Token(msg.text))
                    }
                    is Msg.Ended -> if (arena.live(msg.index)) {
                        arena.jobs.remove(msg.index)
                        val index = msg.index
                        when (val outcome = settle(run, index, arena.startedAt.getValue(index), msg.end)) {
                            Outcome.Succeeded -> {
                                arena.jobs.keys.toList().forEach(arena::supersede)
                                result = run.answer(index, msg.end.text)
                            }
                            is Outcome.Failed -> if (winner == index) {
                                result = run.answer(index, msg.end.text, incomplete = outcome.failure)
                            } else {
                                run.pending = Pending(run.steps[index].provider.id, outcome)
                                // A failure frees a slot at once; there is no reason to wait for the timer.
                                if (arena.jobs.size < limit && startNext(run, arena) != null) schedule()
                            }
                            Outcome.Escalated -> {
                                run.pending = Pending(run.steps[index].provider.id, outcome)
                                if (winner == index) {
                                    // It streamed, then gave up: what is on screen must go, and the rest are tried in order.
                                    run.staleTokens = true
                                    run.discarded = true
                                } else if (arena.jobs.size < limit && startNext(run, arena) != null) {
                                    schedule()
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                if (result != null) break
            }
        } finally {
            timer?.cancel()
            arena.jobs.values.forEach { it.cancel() }
        }
        run.flight.info = null
        result
    }

    /**
     * FastThenBest: an on-device draft is shown while the best remote provider works. The remote
     * answer replaces the draft only when it is complete, so the user never sees it fail half way.
     * Approval for the remote provider is asked before anything is shown.
     */
    private suspend fun FlowCollector<Event>.fastThenBest(run: Run): Result? {
        val draft = run.steps.indices.firstOrNull { run.steps[it].provider.tier == Tier.ON_DEVICE } ?: return null
        val remotes = run.steps.indices.filter { run.steps[it].provider.tier != Tier.ON_DEVICE }
            .sortedByDescending { run.steps[it].provider.quality }
        var best: Int? = null
        var bestStarted = 0L
        for (index in remotes) {
            bestStarted = admit(run, index) ?: continue
            best = index
            break
        }
        if (best == null) return null // no remote provider may take it: the on-device ones run in order
        val draftStarted = admit(run, draft)

        return coroutineScope {
            val arena = Arena(this, run)
            var result: Result? = null
            try {
                with(arena) {
                    start(best, bestStarted)
                    if (draftStarted != null) start(draft, draftStarted)
                }
                val draftText = StringBuilder()
                val bestTokens = ArrayList<String>()
                var draftShown = false
                var draftDone = false
                var live = false // the remote answer is being streamed straight to the app
                while (arena.jobs.isNotEmpty() && result == null) {
                    when (val msg = arena.inbox.receive()) {
                        Msg.Tick -> Unit
                        is Msg.Thinking -> if (arena.live(msg.index)) with(arena) { thinking(msg.index) }
                        is Msg.Token -> if (arena.live(msg.index)) {
                            if (msg.index == draft) {
                                draftText.append(msg.text)
                                draftShown = true
                                emit(Event.Token(msg.text))
                            } else {
                                // The remote provider beat the draft to its first word: no draft is needed.
                                if (!live && !draftShown && !draftDone) {
                                    arena.supersede(draft)
                                    live = true
                                }
                                if (live) emit(Event.Token(msg.text)) else bestTokens += msg.text
                            }
                        }
                        is Msg.Ended -> if (arena.live(msg.index)) {
                            arena.jobs.remove(msg.index)
                            val outcome = settle(run, msg.index, arena.startedAt.getValue(msg.index), msg.end)
                            if (msg.index == draft) {
                                if (outcome == Outcome.Succeeded) {
                                    draftDone = true
                                    // The remote provider has already failed: the draft is the answer.
                                    if (!arena.live(best)) result = run.answer(draft, draftText.toString())
                                } else if (draftShown) {
                                    // A broken draft is on screen; whatever comes next must replace it.
                                    run.staleTokens = true
                                    run.discarded = true
                                    run.pending = Pending(run.steps[draft].provider.id, outcome)
                                }
                            } else when (outcome) {
                                Outcome.Succeeded -> {
                                    arena.supersede(draft)
                                    if (!live && (draftShown || draftDone)) {
                                        emit(Event.Upgrading(run.steps[draft].provider.id, run.steps[best].provider.id))
                                        run.discarded = true
                                        run.staleTokens = false
                                    }
                                    if (!live) bestTokens.forEach { emit(Event.Token(it)) }
                                    result = run.answer(best, msg.end.text)
                                }
                                is Outcome.Failed -> when {
                                    live && msg.end.firstTokenAt != null -> result = run.answer(best, msg.end.text, incomplete = outcome.failure)
                                    draftDone -> result = run.answer(draft, draftText.toString())
                                    else -> run.pending = Pending(run.steps[best].provider.id, outcome)
                                }
                                else -> {
                                    if (live && msg.end.firstTokenAt != null) {
                                        run.staleTokens = true
                                        run.discarded = true
                                    }
                                    if (draftDone) result = run.answer(draft, draftText.toString()) else run.pending = Pending(run.steps[best].provider.id, outcome)
                                }
                            }
                        }
                    }
                }
            } finally {
                arena.jobs.values.forEach { it.cancel() }
            }
            run.flight.info = null
            result
        }
    }

    /**
     * BestOf: several providers answer in full, then one answer is kept. Nothing is streamed
     * until the choice is made, so the app never shows an answer that is then swapped.
     */
    private suspend fun FlowCollector<Event>.bestOf(run: Run, strategy: Strategy.BestOf): Result? = coroutineScope {
        val arena = Arena(this, run)
        val finished = ArrayList<Pair<Int, String>>()
        try {
            while (arena.jobs.size < strategy.count.coerceAtLeast(1) && startNext(run, arena) != null) { }
            while (arena.jobs.isNotEmpty()) {
                when (val msg = arena.inbox.receive()) {
                    is Msg.Thinking -> if (arena.live(msg.index)) with(arena) { thinking(msg.index) }
                    is Msg.Ended -> if (arena.live(msg.index)) {
                        arena.jobs.remove(msg.index)
                        val outcome = settle(run, msg.index, arena.startedAt.getValue(msg.index), msg.end)
                        if (outcome == Outcome.Succeeded) finished += msg.index to msg.end.text
                        else run.pending = Pending(run.steps[msg.index].provider.id, outcome)
                    }
                    else -> Unit // tokens are held back until the choice is made
                }
            }
        } finally {
            arena.jobs.values.forEach { it.cancel() }
        }
        run.flight.info = null
        if (finished.isEmpty()) return@coroutineScope null
        finished.sortBy { it.first } // plan order, whatever order they finished in
        val byQuality = finished.indices.maxByOrNull { run.steps[finished[it].first].provider.quality } ?: 0
        val judge = strategy.judge
        val chosen = if (judge == null || finished.size == 1) {
            byQuality
        } else {
            // A judge that throws, hangs or answers out of range does not lose the request.
            val candidates = finished.map { (index, text) -> Candidate(run.steps[index].provider, text) }
            (guarded(timeouts.hook) { JudgeVerdict(judge.choose(candidates)) })?.index?.takeIf { it in finished.indices } ?: byQuality
        }
        val (index, text) = finished[chosen]
        emit(Event.Token(text))
        run.answer(index, text)
    }

    private class JudgeVerdict(val index: Int)

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
    private suspend fun runAttempt(
        provider: Provider,
        prepared: PreparedRequest,
        limits: AttemptTimeouts,
        onToken: suspend (String) -> Unit,
        onThinking: suspend () -> Unit,
    ): End {
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
                        onToken(event.text)
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
                        onThinking()
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
                    Outcome.Superseded -> "Superseded"
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
