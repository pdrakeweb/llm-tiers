package io.github.pdrakeweb.llmtiers

import io.github.pdrakeweb.llmtiers.testing.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HealthTrackerTest {
    private val clock = FakeClock()
    private val tracker = HealthTracker(clock)
    private val p = ProviderId("p")
    private val home = NetworkId("wifi:home")
    private val away = NetworkId("cell:carrier")

    @Test
    fun `Ready, then Caution on a failure, Out at the threshold, Probing after cooldown, Ready on success`() = runTest {
        assertEquals(HealthState.Ready, tracker.state(p, home))
        tracker.recordFailure(p, home, ProviderFailure.Server(500))
        assertEquals(HealthState.Caution, tracker.state(p, home))
        tracker.recordFailure(p, home, ProviderFailure.Timeout)
        assertEquals(HealthState.Caution, tracker.state(p, home))
        tracker.recordFailure(p, home, ProviderFailure.Stalled)
        assertEquals(HealthState.Out, tracker.state(p, home))
        clock.advance(29.seconds)
        assertEquals(HealthState.Out, tracker.state(p, home))
        clock.advance(1.seconds)
        assertEquals(HealthState.Probing, tracker.state(p, home))
        tracker.recordSuccess(p, home, 100.milliseconds, null)
        assertEquals(HealthState.Ready, tracker.state(p, home))
    }

    @Test
    fun `failures outside the window do not add up`() = runTest {
        repeat(2) { tracker.recordFailure(p, home, ProviderFailure.Server(500)) }
        clock.advance(61.seconds)
        assertEquals(HealthState.Ready, tracker.state(p, home), "old failures have aged out")
        tracker.recordFailure(p, home, ProviderFailure.Server(500))
        assertEquals(HealthState.Caution, tracker.state(p, home))
    }

    @Test
    fun `health is keyed by network - out at one place does not mean out at another`() = runTest {
        tracker.recordFailure(p, away, ProviderFailure.Unreachable)
        assertEquals(HealthState.Out, tracker.state(p, away))
        assertEquals(HealthState.Ready, tracker.state(p, home))
        assertEquals(HealthState.Out, tracker.snapshot(away)[p].state)
        assertEquals(HealthState.Ready, tracker.snapshot(home)[p].state)
    }

    @Test
    fun `Busy and RateLimited cool down briefly`() = runTest {
        tracker.recordFailure(p, home, ProviderFailure.Busy)
        assertEquals(HealthState.Out, tracker.state(p, home))
        clock.advance(10.seconds)
        assertEquals(HealthState.Probing, tracker.state(p, home))
    }

    @Test
    fun `cooldown doubles on each failed probe and is capped`() = runTest {
        tracker.recordFailure(p, home, ProviderFailure.Unreachable)
        var expected = 30.seconds
        repeat(8) {
            clock.advance(expected - 1.milliseconds)
            assertEquals(HealthState.Out, tracker.state(p, home))
            clock.advance(1.milliseconds)
            assertEquals(HealthState.Probing, tracker.state(p, home))
            tracker.recordFailure(p, home, ProviderFailure.Unreachable)
            expected = minOf(expected * 2, 10.minutes)
        }
        assertEquals(10.minutes, expected)
    }

    @Test
    fun `a successful probe is fresh until its TTL, a failure, or another network`() = runTest {
        assertFalse(tracker.probeIsFresh(p, home))
        tracker.recordProbeSuccess(p, home)
        assertTrue(tracker.probeIsFresh(p, home))
        assertFalse(tracker.probeIsFresh(p, away))
        clock.advance(60.seconds)
        assertFalse(tracker.probeIsFresh(p, home))
        tracker.recordProbeSuccess(p, home)
        tracker.recordFailure(p, home, ProviderFailure.Server(500))
        assertFalse(tracker.probeIsFresh(p, home))
    }

    @Test
    fun `typical first-token latency is unknown until there is some history`() = runTest {
        assertNull(tracker.snapshot(home)[p].typicalFirstToken)
        repeat(5) { tracker.recordSuccess(p, home, 400.milliseconds, 50.milliseconds) }
        assertEquals(400.milliseconds, tracker.snapshot(home)[p].typicalFirstToken)
    }

    @Test
    fun `learned timeouts follow the high percentile and stay within bounds`() = runTest {
        repeat(19) { tracker.recordSuccess(p, home, 2.seconds, 1.seconds) }
        tracker.recordSuccess(p, home, 50.seconds, 45.seconds) // one outlier above p95
        val learned = tracker.timeoutsFor(p)
        assertEquals(4.seconds, learned.firstToken, "p95 of 2 s, times the margin of 2")
        assertEquals(2.seconds, learned.stall)

        val slow = ProviderId("slow")
        repeat(20) { tracker.recordSuccess(slow, home, 45.seconds, 40.seconds) }
        assertEquals(AttemptTimeouts(60.seconds, 60.seconds), tracker.timeoutsFor(slow), "capped at the maximum")
    }

    @Test
    fun `reset forgets a provider`() = runTest {
        tracker.recordFailure(p, home, ProviderFailure.AuthRejected)
        assertEquals(HealthState.Out, tracker.state(p, home))
        tracker.reset(p)
        assertEquals(HealthState.Ready, tracker.state(p, home))
    }
}
