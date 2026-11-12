package io.github.pdrakeweb.llmtiers

import io.github.pdrakeweb.llmtiers.Tier.REMOTE
import io.github.pdrakeweb.llmtiers.Tier.ON_DEVICE
import io.github.pdrakeweb.llmtiers.testing.FakeProvider
import io.github.pdrakeweb.llmtiers.testing.SchedulerClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
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

/** Race, Hedged, FastThenBest and BestOf: the strategies that run more than one provider at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class StrategyTest {
    private fun request(strategy: Strategy) = Request(listOf(user("hi")), safeToRetry = true, strategy = strategy)

    private fun TestScope.ai(vararg providers: Pair<FakeProvider, Double>, configure: TieredAiBuilder.() -> Unit = {}): TieredAi =
        TieredAi {
            providers.forEach { (p, quality) -> provider(p, quality) }
            clock = SchedulerClock(testScheduler)
            configure()
        }

    private fun TestScope.ai(vararg providers: FakeProvider, configure: TieredAiBuilder.() -> Unit = {}): TieredAi =
        ai(*providers.map { it to 0.5 }.toTypedArray(), configure = configure)

    private fun List<Event>.result() = (last() as Event.Completed).result
    private fun List<Event>.tokens() = filterIsInstance<Event.Token>().joinToString("") { it.text }
    private fun Result.outcomeOf(id: String): Outcome = when (this) {
        is Result.Answer -> attempts
        is Result.NoneAvailable -> attempts
        is Result.NoVerdict -> attempts
        else -> error("no attempts on $this")
    }.last { it.provider.value == id }.outcome

    private fun slow(id: String, after: Int, text: String = id, tier: Tier = REMOTE) =
        FakeProvider(id, tier) { wait(after.milliseconds); token(text); wait(100.milliseconds); token("!"); done() }

    // ---- Race: first to answer ----------------------------------------------------------------

    @Test
    fun `race - the first provider to produce text wins, the other is cancelled and never shown`() = runTest {
        val a = slow("a", 3_000)
        val b = slow("b", 1_000)
        val events = ai(a, b).stream(request(Strategy.Race())).toList()
        val result = events.result()

        assertIs<Result.Answer>(result)
        assertEquals(ProviderId("b"), result.answeredBy)
        assertEquals("b!", result.text)
        assertEquals("b!", events.tokens(), "only the winner's tokens reach the app")
        assertEquals(Outcome.Superseded, result.outcomeOf("a"))
        assertEquals(Outcome.Succeeded, result.outcomeOf("b"))
        assertEquals(1_100, currentTime, "as fast as the faster one, not the sum")
        assertEquals(listOf(ProviderId("a"), ProviderId("b")), events.filterIsInstance<Event.Started>().map { it.provider })
        assertFalse(result.discardPartial)
    }

    @Test
    fun `race - a provider that fails drops out and the other still answers`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.Server(500)) }
        val b = slow("b", 1_000)
        val result = ai(a, b).generate(request(Strategy.Race()))
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.Server(500)), result.outcomeOf("a"))
    }

    @Test
    fun `race - when every racer fails the rest are tried in order, then NoneAvailable`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.RateLimited) }
        val b = FakeProvider("b", REMOTE) { fail(ProviderFailure.Server(500)) }
        val c = slow("c", 200)
        val result = ai(a, b, c).generate(request(Strategy.Race()))
        assertEquals(ProviderId("c"), (result as Result.Answer).answeredBy, "a failure frees a slot for the next provider")

        val none = ai(
            FakeProvider("a", REMOTE) { fail(ProviderFailure.RateLimited) },
            FakeProvider("b", REMOTE) { fail(ProviderFailure.Server(500)) },
        ).generate(request(Strategy.Race()))
        assertIs<Result.NoneAvailable>(none)
        assertEquals(2, none.attempts.size)
    }

    @Test
    fun `race - no more than maxParallel run at once`() = runTest {
        val a = slow("a", 2_000)
        val b = slow("b", 1_000)
        val c = slow("c", 10)
        val result = ai(a, b, c).generate(request(Strategy.Race(maxParallel = 2)))
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(0, c.calls, "the third was never started although it would have been fastest")
    }

    @Test
    fun `race - a request not marked safe to retry is never sent to two providers`() = runTest {
        val a = slow("a", 3_000)
        val b = slow("b", 1_000)
        val result = ai(a, b).generate(request(Strategy.Race()).copy(safeToRetry = false))
        assertEquals(ProviderId("a"), (result as Result.Answer).answeredBy)
        assertEquals(0, b.calls)
    }

    @Test
    fun `race - a provider the app did not approve is not started`() = runTest {
        val a = slow("a", 10)
        val b = slow("b", 1_000)
        val personal = request(Strategy.Race()).copy(data = listOf(Item("PERSONAL", Sensitivity.Personal)))
        val result = ai(a, b) { approve { _, provider -> provider.id.value == "b" } }.generate(personal)
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(0, a.calls, "the faster provider was refused, so it received nothing")
        assertEquals(Outcome.Skipped(SkipReason.ApprovalDenied), result.outcomeOf("a"))
    }

    @Test
    fun `race - the winner is committed - if it then fails the answer is incomplete, not restarted`() = runTest {
        val a = FakeProvider("a", REMOTE) { token("par"); fail(ProviderFailure.Server(502)) }
        val b = slow("b", 5_000)
        val result = ai(a, b).generate(request(Strategy.Race()))
        assertIs<Result.Answer>(result)
        assertEquals("par", result.text)
        assertEquals(ProviderFailure.Server(502), result.incomplete)
        assertEquals(Outcome.Superseded, result.outcomeOf("b"))
    }

    @Test
    fun `race - a pinned provider is never raced against another`() = runTest {
        val a = slow("a", 3_000)
        val b = slow("b", 10)
        val result = ai(a, b).generate(request(Strategy.Race()).copy(pin = ProviderId("a")))
        assertEquals(ProviderId("a"), (result as Result.Answer).answeredBy)
        assertEquals(0, b.calls)
    }

    @Test
    fun `race - a hard deadline still ends it on time`() = runTest {
        val result = ai(slow("a", 5_000), slow("b", 4_000)).generate(request(Strategy.Race()).copy(deadline = 700.milliseconds))
        assertIs<Result.NoVerdict>(result)
        assertEquals(700, currentTime)
    }

    // ---- Hedged: start a second one only if the first is slow ---------------------------------

    @Test
    fun `hedged - a first provider that answers in time is the only one called`() = runTest {
        val a = slow("a", 500)
        val b = slow("b", 10)
        val result = ai(a, b).generate(request(Strategy.Hedged(after = 2.seconds)))
        assertEquals(ProviderId("a"), (result as Result.Answer).answeredBy)
        assertEquals(0, b.calls, "no second request, no second bill")
    }

    @Test
    fun `hedged - a slow first provider gets company after the delay, and the first text wins`() = runTest {
        val a = slow("a", 9_000)
        val b = slow("b", 500)
        val result = ai(a, b).generate(request(Strategy.Hedged(after = 2.seconds)))
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(2_000 + 500 + 100, currentTime)
        assertEquals(Outcome.Superseded, result.outcomeOf("a"))
    }

    @Test
    fun `hedged - the slow first provider can still win if its text arrives first`() = runTest {
        val a = slow("a", 2_500)
        val b = slow("b", 3_000)
        val result = ai(a, b).generate(request(Strategy.Hedged(after = 2.seconds)))
        assertEquals(ProviderId("a"), (result as Result.Answer).answeredBy)
        assertEquals(1, b.calls)
        assertEquals(Outcome.Superseded, result.outcomeOf("b"))
    }

    @Test
    fun `hedged - a failure starts the next provider at once, without waiting for the delay`() = runTest {
        val a = FakeProvider("a", REMOTE) { wait(100.milliseconds); fail(ProviderFailure.Unreachable) }
        val b = slow("b", 500)
        val result = ai(a, b).generate(request(Strategy.Hedged(after = 30.seconds)))
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy)
        assertEquals(100 + 500 + 100, currentTime)
    }

    @Test
    fun `hedged - thinking is not an answer - a provider that only thinks is still hedged`() = runTest {
        val a = FakeProvider("a", REMOTE) { repeat(20) { wait(1.seconds); thinking() }; token("slow thinker"); done() }
        val b = slow("b", 500)
        val events = ai(a, b).stream(request(Strategy.Hedged(after = 3.seconds))).toList()
        assertEquals(ProviderId("b"), (events.result() as Result.Answer).answeredBy)
        assertTrue(Event.Thinking(ProviderId("a")) in events)
    }

    // ---- FastThenBest: a quick on-device answer, replaced by the best remote one ---------------

    @Test
    fun `fast then best - the on-device draft is shown, then replaced by the complete remote answer`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { wait(200.milliseconds); tokens("quick", " draft"); done() }
        val cloud = FakeProvider("cloud", REMOTE) { wait(3.seconds); tokens("the", " better", " answer"); done() }
        val events = ai(cloud to 0.9, nano to 0.3).stream(request(Strategy.FastThenBest)).toList()
        val result = events.result()

        assertIs<Result.Answer>(result)
        assertEquals("the better answer", result.text)
        assertEquals(ProviderId("cloud"), result.answeredBy)
        assertTrue(result.discardPartial, "the app must replace the draft, not append to it")
        assertEquals(Outcome.Succeeded, result.outcomeOf("nano"))

        val upgrade = events.indexOf(Event.Upgrading(ProviderId("nano"), ProviderId("cloud")))
        assertTrue(upgrade > 0)
        assertEquals("quick draft", events.subList(0, upgrade).tokens(), "the draft streamed first")
        assertEquals("the better answer", events.subList(upgrade, events.size).tokens(), "then the whole better answer")
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `fast then best - if the remote provider fails the draft stands as the answer`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { tokens("quick", " draft"); done() }
        val cloud = FakeProvider("cloud", REMOTE) { wait(2.seconds); token("half"); fail(ProviderFailure.Server(500)) }
        val events = ai(cloud to 0.9, nano to 0.3).stream(request(Strategy.FastThenBest)).toList()
        val result = events.result()

        assertIs<Result.Answer>(result)
        assertEquals("quick draft", result.text)
        assertEquals(ProviderId("nano"), result.answeredBy)
        assertNull(result.incomplete)
        assertFalse(result.discardPartial)
        assertTrue(events.none { it is Event.Upgrading })
        assertEquals("quick draft", events.tokens(), "the half-finished remote answer was never shown")
        assertEquals(Outcome.Failed(ProviderFailure.Server(500)), result.outcomeOf("cloud"))
    }

    @Test
    fun `fast then best - a remote provider that is faster than the draft is simply streamed`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { wait(5.seconds); token("late draft"); done() }
        val cloud = FakeProvider("cloud", REMOTE) { wait(300.milliseconds); tokens("fast", " and good"); done() }
        val events = ai(cloud to 0.9, nano to 0.3).stream(request(Strategy.FastThenBest)).toList()
        val result = events.result() as Result.Answer

        assertEquals("fast and good", result.text)
        assertFalse(result.discardPartial, "nothing was shown that needs replacing")
        assertTrue(events.none { it is Event.Upgrading })
        assertEquals(Outcome.Superseded, result.outcomeOf("nano"))
        assertEquals(300, currentTime)
    }

    @Test
    fun `fast then best - the best remote provider is the one with the highest quality`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { token("draft"); done() }
        val cheap = slow("cheap", 100)
        val premium = slow("premium", 100)
        val result = ai(cheap to 0.3, nano to 0.2, premium to 0.9).generate(request(Strategy.FastThenBest))
        assertEquals(ProviderId("premium"), (result as Result.Answer).answeredBy)
        assertEquals(0, cheap.calls)
    }

    @Test
    fun `fast then best - approval is settled before any draft is shown, and a refusal keeps it on the device`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { token("private answer"); done() }
        val cloud = slow("cloud", 100)
        var askedBeforeDraft = false
        val personal = request(Strategy.FastThenBest).copy(data = listOf(Item("PERSONAL", Sensitivity.Personal)))
        val events = ai(cloud to 0.9, nano to 0.3) {
            approve { _, _ -> askedBeforeDraft = nano.calls == 0; false }
        }.stream(personal).toList()
        val result = events.result() as Result.Answer

        assertTrue(askedBeforeDraft)
        assertEquals(ProviderId("nano"), result.answeredBy)
        assertEquals(0, cloud.calls, "refused: the personal data never left the device")
        assertTrue(events.none { it is Event.Upgrading })
    }

    @Test
    fun `fast then best - a draft that breaks after showing text is replaced by the remote answer`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { token("broken dra"); fail(ProviderFailure.Other("oom")) }
        val cloud = FakeProvider("cloud", REMOTE) { wait(1.seconds); token("whole answer"); done() }
        val events = ai(cloud to 0.9, nano to 0.3).stream(request(Strategy.FastThenBest)).toList()
        val result = events.result() as Result.Answer

        assertEquals("whole answer", result.text)
        assertTrue(result.discardPartial)
        assertTrue(Event.Upgrading(ProviderId("nano"), ProviderId("cloud")) in events)
    }

    @Test
    fun `fast then best - when both fail the request ends NoneAvailable with both reasons`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { fail(ProviderFailure.Busy) }
        val cloud = FakeProvider("cloud", REMOTE) { fail(ProviderFailure.RateLimited) }
        val result = ai(cloud to 0.9, nano to 0.3).generate(request(Strategy.FastThenBest))
        assertIs<Result.NoneAvailable>(result)
        assertEquals(Outcome.Failed(ProviderFailure.Busy), result.outcomeOf("nano"))
        assertEquals(Outcome.Failed(ProviderFailure.RateLimited), result.outcomeOf("cloud"))
    }

    @Test
    fun `fast then best - with nothing on the device it is an ordinary sequential request`() = runTest {
        val a = slow("a", 100)
        val b = slow("b", 100)
        val events = ai(a, b).stream(request(Strategy.FastThenBest)).toList()
        assertEquals(ProviderId("a"), (events.result() as Result.Answer).answeredBy)
        assertEquals(0, b.calls)
    }

    @Test
    fun `fast then best - offline, the draft is simply the answer`() = runTest {
        val nano = FakeProvider("nano", ON_DEVICE) { token("offline answer"); done() }
        val cloud = slow("cloud", 100)
        val offline = io.github.pdrakeweb.llmtiers.testing.ScriptedNetworkMonitor().apply { goOffline() }
        val result = ai(cloud to 0.9, nano to 0.3) { network = offline }.generate(request(Strategy.FastThenBest))
        assertEquals(ProviderId("nano"), (result as Result.Answer).answeredBy)
        assertEquals(0, cloud.calls)
    }

    // ---- BestOf: ask several, keep one --------------------------------------------------------

    @Test
    fun `best of - waits for every answer and keeps the one from the highest-quality provider`() = runTest {
        val a = slow("a", 500, "answer from a")
        val b = slow("b", 2_000, "answer from b")
        val events = ai(a to 0.4, b to 0.8).stream(request(Strategy.BestOf(2))).toList()
        val result = events.result() as Result.Answer

        assertEquals(ProviderId("b"), result.answeredBy)
        assertEquals("answer from b!", result.text)
        assertEquals(listOf(Event.Token("answer from b!")), events.filterIsInstance<Event.Token>(), "nothing is shown until the choice is made")
        assertEquals(Outcome.Succeeded, result.outcomeOf("a"))
        assertEquals(2_100, currentTime, "as slow as the slowest, by design")
    }

    @Test
    fun `best of - a judge chooses between the finished answers`() = runTest {
        val a = slow("a", 100, "short")
        val b = slow("b", 100, "a much longer and more detailed answer")
        val seen = mutableListOf<List<String>>()
        val longest = Judge { candidates ->
            seen += candidates.map { it.provider.id.value }
            candidates.indices.maxBy { candidates[it].text.length }
        }
        val result = ai(a to 0.9, b to 0.1).generate(request(Strategy.BestOf(2, longest)))
        assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy, "the judge overrules the quality ranking")
        assertEquals(listOf(listOf("a", "b")), seen, "candidates come in plan order")
    }

    @Test
    fun `best of - a judge that throws, hangs or points nowhere does not lose the request`() = runTest {
        for (judge in listOf(Judge { error("bug") }, Judge { awaitCancellation() }, Judge { 7 }, Judge { -1 })) {
            val result = ai(slow("a", 100) to 0.4, slow("b", 100) to 0.8).generate(request(Strategy.BestOf(2, judge)))
            assertEquals(ProviderId("b"), (result as Result.Answer).answeredBy, "falls back to the highest quality")
        }
    }

    @Test
    fun `best of - a provider that fails is left out, and if none finish the rest are tried`() = runTest {
        val a = FakeProvider("a", REMOTE) { fail(ProviderFailure.Server(500)) }
        val b = slow("b", 100)
        assertEquals(ProviderId("b"), (ai(a to 0.9, b to 0.1).generate(request(Strategy.BestOf(2))) as Result.Answer).answeredBy)

        val c = slow("c", 100)
        val result = ai(
            FakeProvider("a", REMOTE) { fail(ProviderFailure.Server(500)) } to 0.9,
            FakeProvider("b", REMOTE) { fail(ProviderFailure.RateLimited) } to 0.8,
            c to 0.1,
        ).generate(request(Strategy.BestOf(2)))
        assertEquals(ProviderId("c"), (result as Result.Answer).answeredBy)
    }

    @Test
    fun `best of - asks only as many providers as it was told to`() = runTest {
        val a = slow("a", 100)
        val b = slow("b", 100)
        val c = slow("c", 100)
        ai(a, b, c).generate(request(Strategy.BestOf(2)))
        assertEquals(listOf(1, 1, 0), listOf(a.calls, b.calls, c.calls))
    }

    // ---- ranking ------------------------------------------------------------------------------

    @Test
    fun `ranking by quality tries the best provider first and falls back down the list`() = runTest {
        val cheap = slow("cheap", 10)
        val best = FakeProvider("best", REMOTE) { fail(ProviderFailure.RateLimited) }
        val middle = slow("middle", 10)
        val result = ai(cheap to 0.2, best to 0.9, middle to 0.6)
            .generate(Request(listOf(user("hi")), rank = Ranking.Quality))
        assertEquals(ProviderId("middle"), (result as Result.Answer).answeredBy)
        assertEquals(listOf(ProviderId("best"), ProviderId("middle")), result.attempts.map { it.provider })
        assertEquals(0, cheap.calls)
    }

    @Test
    fun `ranking by speed uses what the library has learned about each provider`() = runTest {
        val slowOne = slow("slow", 3_000)
        val fastOne = slow("fast", 200)
        val ai = ai(slowOne, fastOne)
        repeat(5) { ai.generate(Request(listOf(user("hi")), pin = ProviderId("slow"))) }
        repeat(5) { ai.generate(Request(listOf(user("hi")), pin = ProviderId("fast"))) }
        val result = ai.generate(Request(listOf(user("hi")), rank = Ranking.Speed))
        assertEquals(ProviderId("fast"), (result as Result.Answer).answeredBy)
    }

    @Test
    fun `quality must be between 0 and 1`() {
        val error = runCatching { TieredAi { provider(FakeProvider("a", REMOTE), quality = 1.5) } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
