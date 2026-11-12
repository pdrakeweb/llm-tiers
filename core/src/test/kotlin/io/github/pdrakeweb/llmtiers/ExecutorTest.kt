package io.github.pdrakeweb.llmtiers

import io.github.pdrakeweb.llmtiers.Tier.REMOTE
import io.github.pdrakeweb.llmtiers.Tier.ON_DEVICE
import io.github.pdrakeweb.llmtiers.testing.FakeProvider
import io.github.pdrakeweb.llmtiers.testing.RecordingLogger
import io.github.pdrakeweb.llmtiers.testing.SchedulerClock
import io.github.pdrakeweb.llmtiers.testing.ScriptedNetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutorTest {
    private val hi = Request(listOf(user("hi")), safeToRetry = true)

    private fun TestScope.ai(vararg providers: FakeProvider, configure: TieredAiBuilder.() -> Unit = {}): TieredAi =
        TieredAi {
            providers.forEach(::provider)
            clock = SchedulerClock(testScheduler)
            configure()
        }

    private fun Result.attempts(): List<Attempt> = when (this) {
        is Result.Answer -> attempts
        is Result.NoVerdict -> attempts
        is Result.NoneAvailable -> attempts
        else -> error("no attempts on $this")
    }

    private fun Result.outcomeOf(id: String): Outcome = attempts().last { it.provider.value == id }.outcome

    private val allFailures = listOf(
        ProviderFailure.Unreachable, ProviderFailure.Timeout, ProviderFailure.FirstTokenTimeout,
        ProviderFailure.Stalled, ProviderFailure.AuthRejected, ProviderFailure.KeyMissing,
        ProviderFailure.RateLimited, ProviderFailure.ContextTooLong(1000), ProviderFailure.Server(503),
        ProviderFailure.ModelNotReady, ProviderFailure.Busy, ProviderFailure.LocalNetworkPermissionDenied,
        ProviderFailure.Other("boom"),
    )

    // ---- the happy path and the report -------------------------------------------------------

    @Test
    fun `first provider answers and the result says where it ran and why`() = runTest {
        val a = FakeProvider("a", REMOTE) { tokens("Hel", "lo"); done() }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi)

        assertIs<Result.Answer>(result)
        assertEquals("Hello", result.text)
        assertEquals(ProviderId("a"), result.answeredBy)
        assertEquals(REMOTE, result.tier)
        assertFalse(result.discardPartial)
        assertNull(result.incomplete)
        assertEquals(listOf(Outcome.Succeeded), result.attempts.map { it.outcome })
        assertTrue(result.attempts.single().why.isNotBlank())
        assertEquals(0, b.calls)
    }

    @Test
    fun `stream emits Started, Tokens and exactly one Completed last`() = runTest {
        val a = FakeProvider("a", REMOTE) { tokens("x", "y"); done() }
        val events = ai(a).stream(hi).toList()
        assertEquals(Event.Started(ProviderId("a"), REMOTE), events[0])
        assertEquals(listOf(Event.Token("x"), Event.Token("y")), events.subList(1, 3))
        assertIs<Event.Completed>(events.last())
        assertEquals(1, events.count { it is Event.Completed })
    }

    @Test
    fun `order() overrides registration order`() = runTest {
        val a = FakeProvider("a", REMOTE)
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b) { order("b", "a") }.generate(hi)
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
    }

    // ---- every fallback trigger ---------------------------------------------------------------

    @Test
    fun `every failure type before the first token falls back to the next provider`() = runTest {
        for (failure in allFailures) {
            val a = FakeProvider("a", REMOTE) { fail(failure) }
            val b = FakeProvider("b", REMOTE) { token("from b"); done() }
            val events = ai(a, b).stream(hi).toList()
            val result = (events.last() as Event.Completed).result

            assertIs<Result.Answer>(result, "$failure")
            assertEquals(ProviderId("b"), result.answeredBy, "$failure")
            assertEquals(Outcome.Failed(failure), result.outcomeOf("a"), "$failure")
            assertTrue(
                Event.FellBack(ProviderId("a"), ProviderId("b"), Outcome.Failed(failure), discardPartial = false) in events,
                "$failure: FellBack reported",
            )
            assertFalse(result.discardPartial)
        }
    }

    @Test
    fun `no first token within the timeout falls back`() = runTest {
        val a = FakeProvider("a", REMOTE) { hang() }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi)

        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.FirstTokenTimeout), result.outcomeOf("a"))
        assertEquals(10_000, currentTime, "default first-token timeout is 10 s")
    }

    @Test
    fun `a provider whose flow throws or ends without Done counts as a failure, not a crash`() = runTest {
        val crashes = FakeProvider("crashes", REMOTE) { crash(IllegalStateException("SECRET-IN-MESSAGE")) }
        val silent = FakeProvider("silent", REMOTE) { }
        val b = FakeProvider("b", REMOTE)
        val result = ai(crashes, silent, b).generate(hi)

        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        val crash = assertIs<Outcome.Failed>(result.outcomeOf("crashes")).failure
        assertIs<ProviderFailure.Other>(crash)
        assertFalse("SECRET" in crash.detail, "exception messages may hold content and are never copied")
        assertIs<ProviderFailure.Other>(assertIs<Outcome.Failed>(result.outcomeOf("silent")).failure)
    }

    @Test
    fun `prompt too long skips providers whose context is no longer`() = runTest {
        val small = FakeProvider("small", REMOTE, caps = Capabilities(maxContext = 4_000)) { fail(ProviderFailure.ContextTooLong(4_000)) }
        val same = FakeProvider("same", REMOTE, caps = Capabilities(maxContext = 4_000))
        val big = FakeProvider("big", REMOTE, caps = Capabilities(maxContext = 100_000))
        val result = ai(small, same, big).generate(hi.copy(safeToRetry = false))

        assertEquals(ProviderId("big"), (result as Result.Answer).answeredBy)
        assertEquals(0, same.calls)
        assertEquals(Outcome.Skipped(SkipReason.MissingCapability("context")), result.outcomeOf("same"))
    }

    @Test
    fun `when every provider fails the result is NoneAvailable with every reason`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.RateLimited) }
        val b = FakeProvider("b", REMOTE) { fail(ProviderFailure.Server(500)) }
        val result = ai(a, b).generate(hi)

        assertIs<Result.NoneAvailable>(result)
        assertEquals(
            listOf(Outcome.Failed(ProviderFailure.RateLimited), Outcome.Failed(ProviderFailure.Server(500))),
            result.attempts.map { it.outcome },
        )
    }

    @Test
    fun `no providers at all is NoneAvailable`() = runTest {
        assertEquals(Result.NoneAvailable(emptyList()), ai().generate(hi))
    }

    // ---- first-token commit -------------------------------------------------------------------

    @Test
    fun `a failure after the first token ends the request with the partial text and the reason`() = runTest {
        val a = FakeProvider("a", REMOTE) { tokens("par", "tial"); fail(ProviderFailure.Server(502)) }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi)

        assertIs<Result.Answer>(result)
        assertEquals("partial", result.text)
        assertEquals(ProviderId("a"), result.answeredBy)
        assertEquals(ProviderFailure.Server(502), result.incomplete)
        assertEquals(0, b.calls, "no switching provider after the first token")
    }

    @Test
    fun `a stall after the first token ends the request, it does not fall back`() = runTest {
        val a = FakeProvider("a", REMOTE) { wait(200.milliseconds); token("one"); hang() }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi)

        assertIs<Result.Answer>(result)
        assertEquals("one", result.text)
        assertEquals(ProviderFailure.Stalled, result.incomplete)
        assertEquals(0, b.calls)
        assertEquals(200 + 5_000, currentTime, "default stall timeout is 5 s after the last token")
    }

    @Test
    fun `slow but steady tokens are not a stall`() = runTest {
        val a = FakeProvider("a", REMOTE) {
            repeat(10) { wait(4.seconds); token("t") }
            wait(4.seconds); done()
        }
        val result = ai(a).generate(hi)
        assertEquals("tttttttttt", (result as Result.Answer).text)
        assertNull(result.incomplete)
    }

    // ---- escalation ---------------------------------------------------------------------------

    @Test
    fun `Escalate before any token moves to the next provider without discarding anything`() = runTest {
        val a = FakeProvider("a", ON_DEVICE) { escalate() }
        val b = FakeProvider("b", REMOTE) { token("cloud"); done() }
        val events = ai(a, b).stream(hi.copy(safeToRetry = false)).toList()
        val result = (events.last() as Event.Completed).result as Result.Answer

        assertEquals(ProviderId("b"), result.answeredBy)
        assertFalse(result.discardPartial)
        assertEquals(Outcome.Escalated, result.outcomeOf("a"))
        assertTrue(Event.FellBack(ProviderId("a"), ProviderId("b"), Outcome.Escalated, discardPartial = false) in events)
    }

    @Test
    fun `Escalate after partial output reports discardPartial so the app replaces it`() = runTest {
        val a = FakeProvider("a", ON_DEVICE) { tokens("I think", " maybe"); escalate() }
        val b = FakeProvider("b", REMOTE) { token("definitive"); done() }
        val events = ai(a, b).stream(hi.copy(safeToRetry = false)).toList()
        val result = (events.last() as Event.Completed).result as Result.Answer

        assertEquals("definitive", result.text, "the partial answer is not prepended")
        assertTrue(result.discardPartial)
        val fellBack = events.filterIsInstance<Event.FellBack>().single()
        assertEquals(Event.FellBack(ProviderId("a"), ProviderId("b"), Outcome.Escalated, discardPartial = true), fellBack)
        assertTrue(events.indexOf(fellBack) < events.indexOf(Event.Token("definitive")))
    }

    @Test
    fun `Escalate with nowhere left to go is NoneAvailable`() = runTest {
        val a = FakeProvider("a", ON_DEVICE) { escalate() }
        val result = ai(a).generate(hi)
        assertIs<Result.NoneAvailable>(result)
        assertEquals(Outcome.Escalated, result.outcomeOf("a"))
    }

    // ---- pinning (A-3) ------------------------------------------------------------------------

    @Test
    fun `a pinned provider is never replaced, whatever way it fails`() = runTest {
        for (failure in allFailures) {
            val pinned = FakeProvider("pinned", REMOTE) { fail(failure) }
            val other = FakeProvider("other", REMOTE)
            val events = ai(pinned, other).stream(hi.copy(pin = ProviderId("pinned"))).toList()
            val result = (events.last() as Event.Completed).result

            assertIs<Result.NoneAvailable>(result, "$failure")
            assertEquals(Outcome.Failed(failure), result.outcomeOf("pinned"))
            assertEquals(Outcome.Skipped(SkipReason.NotPinned), result.outcomeOf("other"))
            assertEquals(0, other.calls, "$failure: the other provider must never run")
            assertTrue(events.none { it is Event.FellBack })
        }
    }

    @Test
    fun `a pinned provider that hangs or escalates is not replaced either`() = runTest {
        val other = FakeProvider("other", REMOTE)
        val hangs = FakeProvider("p", ON_DEVICE) { hang() }
        assertIs<Result.NoneAvailable>(ai(hangs, other).generate(hi.copy(pin = ProviderId("p"))))
        val escalates = FakeProvider("p", ON_DEVICE) { token("partial"); escalate() }
        assertIs<Result.NoneAvailable>(ai(escalates, other).generate(hi.copy(pin = ProviderId("p"))))
        assertEquals(0, other.calls)
    }

    @Test
    fun `a pinned provider answers even when another is preferred by mode`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE)
        val cloud = FakeProvider("cloud", REMOTE)
        val result = ai(nano, cloud).generate(hi.copy(pin = ProviderId("cloud"), mode = Mode.PreferOnDevice))
        assertEquals(ProviderId("cloud"), (result as Result.Answer).answeredBy)
        assertEquals(0, nano.calls)
    }

    // ---- retry safety -------------------------------------------------------------------------

    @Test
    fun `a request not marked safe to retry is not replayed after an ambiguous failure`() = runTest {
        val ambiguous = listOf(
            ProviderFailure.Timeout, ProviderFailure.FirstTokenTimeout, ProviderFailure.Stalled,
            ProviderFailure.Server(500), ProviderFailure.Other("?"),
        )
        for (failure in ambiguous) {
            val a = FakeProvider("a", REMOTE) { fail(failure) }
            val b = FakeProvider("b", REMOTE)
            val result = ai(a, b).generate(hi.copy(safeToRetry = false))

            assertIs<Result.NoneAvailable>(result, "$failure")
            assertEquals(0, b.calls, "$failure: must not be replayed")
            assertEquals(Outcome.Skipped(SkipReason.NotSafeToRetry), result.outcomeOf("b"))
        }
    }

    @Test
    fun `a first-token timeout is ambiguous too`() = runTest {
        val a = FakeProvider("a", REMOTE) { hang() }
        val b = FakeProvider("b", REMOTE)
        assertIs<Result.NoneAvailable>(ai(a, b).generate(hi.copy(safeToRetry = false)))
        assertEquals(0, b.calls)
    }

    @Test
    fun `an idempotency key allows the replay`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.Server(500)) }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi.copy(safeToRetry = false, idempotencyKey = "k-1"))
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals("k-1", b.received.single().idempotencyKey)
    }

    @Test
    fun `failures that prove the request was not processed fall back without the retry mark`() = runTest {
        val definite = listOf(
            ProviderFailure.Unreachable, ProviderFailure.AuthRejected, ProviderFailure.KeyMissing,
            ProviderFailure.RateLimited, ProviderFailure.ModelNotReady, ProviderFailure.Busy,
            ProviderFailure.LocalNetworkPermissionDenied,
        )
        for (failure in definite) {
            val a = FakeProvider("a", REMOTE) { fail(failure) }
            val b = FakeProvider("b", REMOTE)
            val result = ai(a, b).generate(hi.copy(safeToRetry = false))
            assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy, "$failure")
        }
    }

    // ---- health and cooldown ------------------------------------------------------------------

    @Test
    fun `repeated failures take a provider out, cooldown lets it be probed back, success restores it`() = runTest {
        val net = NetworkState.Unknown.id
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.Server(500)) }
        val b = FakeProvider("b", REMOTE)
        val ai = ai(a, b)
        val idA = ProviderId("a")

        assertEquals(HealthState.Ready, ai.health.state(idA, net))
        ai.generate(hi)
        assertEquals(HealthState.Caution, ai.health.state(idA, net))
        ai.generate(hi)
        ai.generate(hi)
        assertEquals(HealthState.Out, ai.health.state(idA, net), "third failure in the window")
        assertEquals(3, a.calls)

        val skipped = ai.generate(hi)
        assertEquals(3, a.calls, "a provider that is out is not called")
        assertEquals(Outcome.Skipped(SkipReason.CoolingDown), skipped.outcomeOf("a"))
        assertEquals(ProviderId("b"), (skipped as Result.Answer).answeredBy)

        delay(30.seconds)
        assertEquals(HealthState.Probing, ai.health.state(idA, net))
        a.always { token("back"); done() }
        val recovered = ai.generate(hi)
        assertEquals(ProviderId("a"), (recovered as Result.Answer).answeredBy)
        assertEquals(HealthState.Ready, ai.health.state(idA, net))
    }

    @Test
    fun `a provider that fails while probing goes out again for twice as long`() = runTest {
        val net = NetworkState.Unknown.id
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.Unreachable) }
        val b = FakeProvider("b", REMOTE)
        val ai = ai(a, b)
        val idA = ProviderId("a")

        ai.generate(hi)
        assertEquals(HealthState.Out, ai.health.state(idA, net), "unreachable takes a provider out at once")
        delay(30.seconds)
        assertEquals(HealthState.Probing, ai.health.state(idA, net))
        ai.generate(hi)
        assertEquals(2, a.calls)
        delay(30.seconds)
        assertEquals(HealthState.Out, ai.health.state(idA, net), "second cooldown is 60 s")
        delay(30.seconds)
        assertEquals(HealthState.Probing, ai.health.state(idA, net))
    }

    @Test
    fun `prompt too long is about the request, not the provider's health`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.ContextTooLong(4096)) }
        val ai = ai(a, FakeProvider("b", REMOTE, caps = Capabilities(maxContext = 100_000)))
        repeat(5) { ai.generate(hi) }
        assertEquals(HealthState.Ready, ai.health.state(ProviderId("a"), NetworkState.Unknown.id))
    }

    @Test
    fun `timeouts are learned per provider once it has 20 samples`() = runTest {
        val a = FakeProvider("a", REMOTE) { wait(100.milliseconds); token("t"); wait(50.milliseconds); done() }
        val ai = ai(a)
        repeat(20) { ai.generate(hi) }
        val learned = ai.health.timeoutsFor(ProviderId("a"))
        assertEquals(1.seconds, learned.firstToken, "p95 x margin, never below the 1 s floor")
        assertEquals(1.seconds, learned.stall)

        a.always { hang() }
        val before = currentTime
        val result = ai.generate(hi)
        assertEquals(Outcome.Failed(ProviderFailure.FirstTokenTimeout), result.outcomeOf("a"))
        assertEquals(1_000, currentTime - before, "the learned timeout replaced the 10 s default")
    }

    @Test
    fun `fewer than 20 samples keep the fixed defaults`() = runTest {
        val a = FakeProvider("a", REMOTE) { wait(100.milliseconds); token("t"); done() }
        val ai = ai(a)
        repeat(19) { ai.generate(hi) }
        assertEquals(AttemptTimeouts(10.seconds, 5.seconds), ai.health.timeoutsFor(ProviderId("a")))
    }

    // ---- thinking (reasoning models) ----------------------------------------------------------

    @Test
    fun `a provider that is thinking is alive - it may take longer than the first-token timeout`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true) {
            repeat(30) { wait(1.seconds); thinking() }
            token("answer"); done()
        }
        val b = FakeProvider("b", REMOTE)
        val events = ai(a, b).stream(hi).toList()
        val result = (events.last() as Event.Completed).result

        assertIs<Result.Answer>(result, "30 s of thinking must not be read as 10 s of silence")
        assertEquals(ProviderId("a"), result.answeredBy)
        assertEquals(0, b.calls)
        assertEquals(30.seconds, result.attempts.single().firstTokenAfter, "first token time is the first answer text")
        assertEquals(1, events.count { it is Event.Thinking }, "the app is told once, not thirty times")
        assertTrue(events.indexOf(Event.Thinking(ProviderId("a"))) < events.indexOf(Event.Token("answer")))
        assertTrue(events.indexOf(Event.Started(ProviderId("a"), REMOTE)) < events.indexOf(Event.Thinking(ProviderId("a"))))
    }

    @Test
    fun `thinking that goes quiet is a first-token timeout after the stall timeout`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true) { wait(2.seconds); thinking(); hang() }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi)
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.FirstTokenTimeout), result.outcomeOf("a"))
        assertEquals(2_000 + 5_000, currentTime, "5 s without a sign of life, not the full thinking allowance")
    }

    @Test
    fun `thinking is not unlimited - it is capped, so a model that never answers cannot hold the request`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true) { repeat(100_000) { wait(1.seconds); thinking() } }
        val b = FakeProvider("b", REMOTE)
        val result = ai(a, b).generate(hi)
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.FirstTokenTimeout), result.outcomeOf("a"))
        assertEquals(120_000, currentTime, "the default thinking allowance is two minutes")
    }

    @Test
    fun `the thinking allowance is configurable`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true) { repeat(100_000) { wait(1.seconds); thinking() } }
        val result = ai(a) { timeouts = TimeoutConfig(thinking = 20.seconds) }.generate(hi)
        assertIs<Result.NoneAvailable>(result)
        assertEquals(20_000, currentTime)
    }

    @Test
    fun `a provider that neither thinks nor answers still gets only the first-token timeout`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true) { wait(11.seconds); thinking(); token("late"); done() }
        val result = ai(a).generate(hi)
        assertIs<Result.NoneAvailable>(result)
        assertEquals(10_000, currentTime, "thinking has to start within the first-token timeout")
    }

    @Test
    fun `thinking between tokens keeps a long answer from being called stalled`() = runTest {
        val a = FakeProvider("a", REMOTE) {
            token("part one, ")
            repeat(8) { wait(1.seconds); thinking() }
            token("part two"); done()
        }
        val events = ai(a).stream(hi).toList()
        val result = (events.last() as Event.Completed).result as Result.Answer
        assertEquals("part one, part two", result.text)
        assertNull(result.incomplete, "8 s between tokens, but never 5 s without a sign of life")
    }

    @Test
    fun `timeouts are learned from the first sign of life, latency from the first answer text`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true) { wait(100.milliseconds); thinking(); wait(3.seconds); token("t"); done() }
        val ai = ai(a)
        repeat(20) { ai.generate(hi) }
        assertEquals(
            1.seconds, ai.health.timeoutsFor(ProviderId("a")).firstToken,
            "the model shows life in 100 ms; 3 s of thinking must not stretch the timeout for silence",
        )
        assertEquals(
            3100.milliseconds, ai.health.snapshot(NetworkState.Unknown.id)[ProviderId("a")].typicalFirstToken,
            "for latency budgets what counts is when the answer starts",
        )
    }

    @Test
    fun `a thinking provider that then fails or escalates is handled like any other`() = runTest {
        val fails = FakeProvider("fails", REMOTE, probeBeforeUse = true) { thinking(); fail(ProviderFailure.Server(500)) }
        val escalates = FakeProvider("escalates", ON_DEVICE) { thinking(); escalate() }
        val b = FakeProvider("b", REMOTE)
        val result = ai(fails, escalates, b).generate(hi)
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.Server(500)), result.outcomeOf("fails"))
        assertEquals(Outcome.Escalated, result.outcomeOf("escalates"))
        assertFalse(result.discardPartial, "thinking is not partial output")
    }

    @Test
    fun `a hard deadline still ends a thinking provider on time`() = runTest {
        val a = FakeProvider("a", ON_DEVICE) { repeat(100) { wait(100.milliseconds); thinking() } }
        assertIs<Result.NoVerdict>(ai(a).generate(hi.copy(deadline = 700.milliseconds)))
        assertEquals(700, currentTime)
    }

    // ---- hard deadline ------------------------------------------------------------------------

    @Test
    fun `a missed hard deadline is NoVerdict, exactly at the deadline, with no slower fallback`() = runTest {
        val fast = FakeProvider("fast", ON_DEVICE) { wait(2.seconds); token("late"); done() }
        val slow = FakeProvider("slow", REMOTE)
        val result = ai(fast, slow).generate(hi.copy(deadline = 700.milliseconds))

        assertIs<Result.NoVerdict>(result)
        assertEquals(700, currentTime)
        assertEquals(0, slow.calls)
    }

    @Test
    fun `a deadline that is met gives a normal answer`() = runTest {
        val fast = FakeProvider("fast", ON_DEVICE) { wait(300.milliseconds); token("yes"); done() }
        val result = ai(fast).generate(hi.copy(deadline = 700.milliseconds))
        assertEquals("yes", (result as Result.Answer).text)
    }

    @Test
    fun `a deadline that expires mid-answer is NoVerdict, not a partial answer`() = runTest {
        val a = FakeProvider("a", ON_DEVICE) { token("par"); wait(1.seconds); token("tial"); done() }
        val result = ai(a).generate(hi.copy(deadline = 700.milliseconds))
        assertIs<Result.NoVerdict>(result)
    }

    @Test
    fun `a deadline covers fallbacks too and reports what was tried`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.Unreachable) }
        val b = FakeProvider("b", REMOTE) { wait(5.seconds); token("late"); done() }
        val result = ai(a, b).generate(hi.copy(deadline = 700.milliseconds))
        assertIs<Result.NoVerdict>(result)
        assertEquals(Outcome.Failed(ProviderFailure.Unreachable), result.outcomeOf("a"))
        assertEquals(700, currentTime)
    }

    // ---- short-circuit (A-3) ------------------------------------------------------------------

    @Test
    fun `a short-circuit answer is returned in every mode and never reaches a model`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE)
        val cloud = FakeProvider("cloud", REMOTE)
        val monitor = ScriptedNetworkMonitor()
        val ai = ai(nano, cloud) {
            network = monitor
            shortCircuit { request -> if (request.task == "emergency") "CALL THE COASTGUARD" else null }
        }
        val emergency = hi.copy(task = "emergency")

        for (mode in Mode.entries) {
            assertEquals(Result.Answered("CALL THE COASTGUARD"), ai.generate(emergency.copy(mode = mode)), "$mode")
        }
        assertEquals(Result.Answered("CALL THE COASTGUARD"), ai.generate(emergency.copy(pin = ProviderId("cloud"))))
        monitor.goOffline()
        assertEquals(Result.Answered("CALL THE COASTGUARD"), ai.generate(emergency))
        assertEquals(0, nano.calls + cloud.calls)
        assertEquals(0, nano.probes + cloud.probes)

        val events = ai.stream(emergency).toList()
        assertEquals(listOf<Event>(Event.Completed(Result.Answered("CALL THE COASTGUARD"))), events)
    }

    @Test
    fun `a short-circuit hook that declines lets the request route normally`() = runTest {
        val cloud = FakeProvider("cloud", REMOTE)
        val result = ai(cloud) { shortCircuit { null } }.generate(hi)
        assertIs<Result.Answer>(result)
    }

    @Test
    fun `a short-circuit hook that throws or hangs fails closed - no model is called`() = runTest {
        val cloud = FakeProvider("cloud", REMOTE)
        val throwing = ai(cloud) { shortCircuit { error("hook bug") } }.generate(hi)
        assertIs<Result.Blocked>(throwing)

        val hanging = ai(cloud) { shortCircuit { awaitCancellation() } }.generate(hi)
        assertIs<Result.Blocked>(hanging)
        assertEquals(0, cloud.calls)
    }

    // ---- privacy (A-1) ------------------------------------------------------------------------

    private val personal = Item("PERSONAL-knee-injury", Sensitivity.Personal, "conditions")
    private val public = Item("PUBLIC-30-minutes", Sensitivity.Public, "limit")
    private val identifier = Item("IDENTIFIER-jane@example.com", Sensitivity.Identifier, "email")

    private fun FakeProvider.everythingReceived(): String = received.joinToString("\n") { r ->
        r.items.joinToString { it.value } + r.renderedMessages().joinToString { m ->
            m.parts.filterIsInstance<Part.Text>().joinToString { it.text }
        }
    }

    @Test
    fun `Identifier items never reach any provider`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { escalate() }
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true) { fail(ProviderFailure.Unreachable) }
        val cloud = FakeProvider("cloud", REMOTE)
        val result = ai(nano, home, cloud) { approve { _, _ -> true } }
            .generate(hi.copy(data = listOf(public, personal, identifier)))

        assertIs<Result.Answer>(result)
        for (p in listOf(nano, home, cloud)) {
            assertEquals(1, p.calls)
            assertFalse("IDENTIFIER" in p.everythingReceived(), "${p.id} received an Identifier item")
            assertTrue("PUBLIC-30-minutes" in p.everythingReceived())
        }
    }

    @Test
    fun `Personal items reach a remote provider only after approval for that provider`() = runTest {
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true) { fail(ProviderFailure.Unreachable) }
        val cloud = FakeProvider("cloud", REMOTE)
        val asked = mutableListOf<Pair<List<Item>, ProviderId>>()
        val result = ai(home, cloud) {
            approve { request, provider ->
                asked += request.data to provider.id
                provider.id.value == "cloud"
            }
        }.generate(hi.copy(data = listOf(personal, identifier)))

        assertEquals(ProviderId("cloud"), (result as Result.Answer).answeredBy)
        assertEquals(0, home.calls, "denied provider receives nothing")
        assertEquals(0, home.probes, "denied provider is not even probed")
        assertEquals(Outcome.Skipped(SkipReason.ApprovalDenied), result.outcomeOf("home"))
        assertTrue("PERSONAL-knee-injury" in cloud.everythingReceived())
        assertEquals(listOf(ProviderId("home"), ProviderId("cloud")), asked.map { it.second })
        assertTrue(asked.all { (items, _) -> items == listOf(personal) }, "the approval preview shows exactly what would be sent")
    }

    @Test
    fun `approval is asked again for every request`() = runTest {
        val cloud = FakeProvider("cloud", REMOTE)
        var asks = 0
        val ai = ai(cloud) { approve { _, _ -> asks++; true } }
        repeat(3) { ai.generate(hi.copy(data = listOf(personal))) }
        assertEquals(3, asks)
    }

    @Test
    fun `on-device providers get Personal items without approval`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE)
        var asks = 0
        val result = ai(nano) { approve { _, _ -> asks++; false } }.generate(hi.copy(data = listOf(personal)))
        assertIs<Result.Answer>(result)
        assertEquals(0, asks)
        assertTrue("PERSONAL-knee-injury" in nano.everythingReceived())
    }

    @Test
    fun `when approval is refused everywhere the request is Blocked and nothing is sent`() = runTest {
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true)
        val cloud = FakeProvider("cloud", REMOTE)
        val result = ai(home, cloud) { approve { _, _ -> false } }.generate(hi.copy(data = listOf(personal)))

        assertIs<Result.Blocked>(result)
        assertTrue(result.reason.isNotBlank())
        assertFalse("PERSONAL" in result.reason)
        assertEquals(0, home.calls + cloud.calls)
    }

    @Test
    fun `without an approve callback Personal data never leaves the device`() = runTest {
        val cloud = FakeProvider("cloud", REMOTE)
        assertIs<Result.Blocked>(ai(cloud).generate(hi.copy(data = listOf(personal))))
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `an approve callback that throws or never returns counts as a refusal`() = runTest {
        val cloud = FakeProvider("cloud", REMOTE)
        assertIs<Result.Blocked>(ai(cloud) { approve { _, _ -> error("ui gone") } }.generate(hi.copy(data = listOf(personal))))
        assertIs<Result.Blocked>(ai(cloud) { approve { _, _ -> awaitCancellation() } }.generate(hi.copy(data = listOf(personal))))
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `a provider that does not accept Personal data blocks rather than sends`() = runTest {
        val cloud = FakeProvider("cloud", REMOTE, accepts = setOf(Sensitivity.Public))
        val result = ai(cloud) { approve { _, _ -> true } }.generate(hi.copy(data = listOf(personal)))
        assertIs<Result.Blocked>(result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `logs carry provider ids, timings and failure types - never content`() = runTest {
        val logger = RecordingLogger()
        val a = FakeProvider("a", REMOTE) { token("RESPONSE-partial"); escalate() }
        val b = FakeProvider("b", REMOTE) { fail(ProviderFailure.Other("DETAIL-from-server")) }
        val c = FakeProvider("c", REMOTE) { crash(RuntimeException("EXCEPTION-text")) }
        val d = FakeProvider("d", REMOTE) { token("RESPONSE-final"); done() }
        val request = Request(
            listOf(system("PROMPT-system"), user("PROMPT-user")),
            data = listOf(public, personal, identifier),
            safeToRetry = true,
            task = "TASK-hint",
            idempotencyKey = "KEY-idem",
        )
        ai(a, b, c, d) { this.logger = logger; approve { _, _ -> true } }.generate(request)

        val all = logger.records.joinToString("\n")
        assertTrue(logger.records.isNotEmpty())
        assertTrue(logger.records.any { it.provider == ProviderId("d") })
        for (marker in listOf("RESPONSE", "PROMPT", "PUBLIC", "PERSONAL", "IDENTIFIER", "DETAIL", "EXCEPTION", "TASK-", "KEY-")) {
            assertFalse(marker in all, "log contains $marker: $all")
        }
        assertFalse("PERSONAL" in request.toString() || "PROMPT" in request.toString(), "toString() of a request is content-free")
    }

    // ---- networks and the REMOTE probe -----------------------------------------------------------

    @Test
    fun `own server when at home, cloud when away within 1_5 s, and back on return`() = runTest {
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true) { token("lan"); done() }
        val cloud = FakeProvider("cloud", REMOTE) { token("cloud"); done() }
        val monitor = ScriptedNetworkMonitor(ScriptedNetworkMonitor.wifi("home"))
        val ai = ai(home, cloud) { network = monitor }

        assertEquals(ProviderId("home"), (ai.generate(hi.copy(safeToRetry = false)) as Result.Answer).answeredBy)

        // Leave home: the REMOTE address no longer answers at all.
        monitor.switchTo(ScriptedNetworkMonitor.cellular())
        home.probeHangs().always { hang() }
        var start = currentTime
        val away = ai.generate(hi.copy(safeToRetry = false))
        assertEquals(ProviderId("cloud"), (away as Result.Answer).answeredBy)
        assertTrue(currentTime - start <= 1_500, "failover took ${currentTime - start} ms")
        assertEquals(Outcome.Failed(ProviderFailure.Unreachable), away.outcomeOf("home"))
        assertEquals(1, home.calls, "the request itself was never sent to the unreachable home server")

        // Still away: no second wait.
        start = currentTime
        val stillAway = ai.generate(hi)
        assertEquals(ProviderId("cloud"), (stillAway as Result.Answer).answeredBy)
        assertEquals(0, currentTime - start, "stays failed over without probing again")
        assertEquals(Outcome.Skipped(SkipReason.CoolingDown), stillAway.outcomeOf("home"))

        // Come home: switch back without a restart.
        monitor.switchTo(ScriptedNetworkMonitor.wifi("home"))
        home.probeReachable().always { token("lan"); done() }
        assertEquals(ProviderId("home"), (ai.generate(hi) as Result.Answer).answeredBy)
    }

    @Test
    fun `a provider that refuses its probe is not sent the request`() = runTest {
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true).probeUnreachable(ProviderFailure.LocalNetworkPermissionDenied)
        val cloud = FakeProvider("cloud", REMOTE)
        val events = ai(home, cloud).stream(hi.copy(safeToRetry = false)).toList()
        val result = (events.last() as Event.Completed).result as Result.Answer

        assertEquals(ProviderId("cloud"), result.answeredBy)
        assertEquals(0, home.calls)
        assertEquals(Outcome.Failed(ProviderFailure.LocalNetworkPermissionDenied), result.outcomeOf("home"))
        assertFalse(Event.Started(ProviderId("home"), REMOTE) in events)
    }

    @Test
    fun `a successful probe is cached per network, a cloud provider is never probed`() = runTest {
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true)
        val cloud = FakeProvider("cloud", REMOTE)
        val monitor = ScriptedNetworkMonitor(ScriptedNetworkMonitor.wifi("home"))
        val ai = ai(home, cloud) { network = monitor }
        repeat(3) { ai.generate(hi) }
        assertEquals(1, home.probes, "three requests on one network, one probe")
        delay(60.seconds)
        ai.generate(hi)
        assertEquals(2, home.probes, "probed again once the cached result is 60 s old")
        monitor.switchTo(ScriptedNetworkMonitor.wifi("office"))
        ai.generate(hi)
        assertEquals(3, home.probes, "a different network is probed afresh")

        home.always { fail(ProviderFailure.Server(500)) }
        ai.generate(hi)
        assertEquals(ProviderId("cloud"), (ai.generate(hi) as Result.Answer).answeredBy)
        assertEquals(4, home.probes, "a failure invalidates the cached probe")
        assertEquals(0, cloud.probes, "a degraded connection is tried, not pre-emptively avoided")
    }

    @Test
    fun `offline answers on device and never tries the network tiers`() = runTest {
        val home = FakeProvider("home", REMOTE, probeBeforeUse = true)
        val cloud = FakeProvider("cloud", REMOTE)
        val nano = FakeProvider("nano", ON_DEVICE) { token("offline answer"); done() }
        val monitor = ScriptedNetworkMonitor().apply { goOffline() }
        val result = ai(home, cloud, nano) { network = monitor }.generate(hi)

        assertEquals(ProviderId("nano"), (result as Result.Answer).answeredBy)
        assertEquals(0, home.calls + cloud.calls + home.probes)
        assertEquals(Outcome.Skipped(SkipReason.Offline), result.outcomeOf("cloud"))
    }

    @Test
    fun `offline with no on-device provider is NoneAvailable with the reason, immediately`() = runTest {
        val monitor = ScriptedNetworkMonitor().apply { goOffline() }
        val result = ai(FakeProvider("cloud", REMOTE)) { network = monitor }.generate(hi)
        assertEquals(Result.NoneAvailable(listOf(Attempt(ProviderId("cloud"), REMOTE, Outcome.Skipped(SkipReason.Offline)))), result)
        assertEquals(0, currentTime)
    }

    @Test
    fun `an on-device model that is not ready is probed out and the next provider answers`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE).probeUnreachable(ProviderFailure.ModelNotReady)
        val cloud = FakeProvider("cloud", REMOTE)
        val result = ai(nano, cloud).generate(hi.copy(safeToRetry = false))
        assertEquals(ProviderId("cloud"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.ModelNotReady), result.outcomeOf("nano"))
    }

    // ---- never hang (A-2) ---------------------------------------------------------------------

    @Test
    fun `a provider that never reports its capabilities is skipped, not waited for`() = runTest {
        val stuck = FakeProvider("stuck", REMOTE).apply { onCapabilities = { awaitCancellation() } }
        val ok = FakeProvider("ok", REMOTE)
        val result = ai(stuck, ok).generate(hi)
        assertEquals(ProviderId("ok"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Skipped(SkipReason.CapabilitiesUnavailable), result.outcomeOf("stuck"))
        assertEquals(5_000, currentTime)
    }

    @Test
    fun `callbacks that ignore cancellation still cannot hang the request`() = runTest {
        // suspendCoroutine is not cancellable: a dialog the user never answers looks like this.
        suspend fun never(): Nothing = kotlin.coroutines.suspendCoroutine { }
        val cloud = FakeProvider("cloud", REMOTE)
        val personal = hi.copy(data = listOf(Item("p", Sensitivity.Personal)))

        assertIs<Result.Blocked>(ai(cloud) { approve { _, _ -> never() } }.generate(personal))
        assertIs<Result.Blocked>(ai(cloud) { shortCircuit { never() } }.generate(hi))
        assertEquals(0, cloud.calls)

        val stuckCaps = FakeProvider("stuck", REMOTE).apply { onCapabilities = { never() } }
        val stuckProbe = FakeProvider("lan", REMOTE, probeBeforeUse = true).apply { onProbe = { never() } }
        val result = ai(stuckCaps, stuckProbe, cloud).generate(hi)
        assertEquals(ProviderId("cloud"), (result as Result.Answer).answeredBy)

        val deadline = ai(cloud) { approve { _, _ -> never() } }.generate(personal.copy(deadline = 700.milliseconds))
        assertIs<Result.NoVerdict>(deadline)
    }

    @Test
    fun `a network monitor or logger that throws does not break the request`() = runTest {
        val broken = object : NetworkMonitor {
            override val state: kotlinx.coroutines.flow.StateFlow<NetworkState> get() = error("monitor bug")
        }
        val result = ai(FakeProvider("cloud", REMOTE)) {
            network = broken
            logger = Logger { error("logger bug") }
        }.generate(hi)
        assertIs<Result.Answer>(result)
    }

    @Test
    fun `a learned timeout that fires is forgotten, so a provider that got slower is not locked out`() = runTest {
        val a = FakeProvider("a", REMOTE) { wait(100.milliseconds); token("t"); done() }
        val ai = ai(a)
        repeat(20) { ai.generate(hi) }
        assertEquals(1.seconds, ai.health.timeoutsFor(ProviderId("a")).firstToken)

        a.always { wait(2.seconds); token("slower now"); done() }
        assertIs<Result.NoneAvailable>(ai.generate(hi), "the first request after the slowdown times out")
        assertEquals(10.seconds, ai.health.timeoutsFor(ProviderId("a")).firstToken, "back to the default")
        assertEquals("slower now", (ai.generate(hi.copy(pin = ProviderId("a"))) as Result.Answer).text)
    }

    @Test
    fun `results and tokens do not put response text in toString`() = runTest {
        val a = FakeProvider("a", REMOTE) { token("RESPONSE-TEXT"); done() }
        val events = ai(a).stream(hi).toList()
        assertFalse("RESPONSE-TEXT" in events.toString())
        assertFalse("RESPONSE-TEXT" in ProviderEvent.Token("RESPONSE-TEXT").toString())
    }

    @Test
    fun `whatever the providers do, the request ends in a typed result in bounded time`() = runTest {
        val a = FakeProvider("a", REMOTE, probeBeforeUse = true).probeHangs()
        val b = FakeProvider("b", ON_DEVICE) { hang() }
        val c = FakeProvider("c", REMOTE) { token("x"); hang() }
        val result = ai(a, b, c).generate(hi)

        assertIs<Result.Answer>(result)
        assertEquals(ProviderFailure.Stalled, result.incomplete)
        assertEquals(1_000 + 10_000 + 5_000, currentTime, "probe + first-token + stall timeouts")
    }

    @Test
    fun `cancelling the caller cancels the provider`() = runTest {
        var cancelled = false
        val a = object : Provider by FakeProvider("a", REMOTE) {
            override fun stream(request: PreparedRequest) = kotlinx.coroutines.flow.flow<ProviderEvent> {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val ai = TieredAi { provider(a); clock = SchedulerClock(testScheduler) }
        val job = launch { ai.generate(hi) }
        delay(100)
        job.cancel()
        job.join()
        assertTrue(cancelled)
    }
}
