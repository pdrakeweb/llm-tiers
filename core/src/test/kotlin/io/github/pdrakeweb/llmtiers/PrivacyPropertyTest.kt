package io.github.pdrakeweb.llmtiers

import io.github.pdrakeweb.llmtiers.testing.FakeProvider
import io.github.pdrakeweb.llmtiers.testing.RecordingLogger
import io.github.pdrakeweb.llmtiers.testing.SchedulerClock
import io.github.pdrakeweb.llmtiers.testing.ScriptedNetworkMonitor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Invariant A-1 as a property: for random requests, provider sets, provider behaviours, approval
 * answers, modes, pins and networks, no `Identifier` item and no unapproved `Personal` item ever
 * reaches a REMOTE or cloud provider, and nothing sensitive is logged.
 *
 * Deterministic: case N uses `Random(SEED + N)`, and a failure names the case to replay.
 */
class PrivacyPropertyTest {
    private val failures = listOf(
        ProviderFailure.Unreachable, ProviderFailure.Timeout, ProviderFailure.AuthRejected, ProviderFailure.KeyMissing,
        ProviderFailure.RateLimited, ProviderFailure.ContextTooLong(2048), ProviderFailure.Server(500),
        ProviderFailure.ModelNotReady, ProviderFailure.Busy, ProviderFailure.LocalNetworkPermissionDenied,
        ProviderFailure.Other("x"),
    )

    @Test
    fun `no Identifier and no unapproved Personal item reaches a remote provider`() = runTest(timeout = 5.minutes) {
        var personalSentWithApproval = 0
        var blocked = 0
        var answered = 0
        repeat(CASES) { case ->
            val rnd = Random(SEED + case)
            fun where() = "case $case (seed ${SEED + case})"

            val providers = List(rnd.nextInt(0, 6)) { i ->
                val accepts = Sensitivity.entries.filter { rnd.nextInt(4) != 0 }.toSet()
                FakeProvider("p$i", Tier.entries.random(rnd), accepts, Capabilities(maxContext = rnd.nextInt(1, 5) * 2048)).apply {
                    always {
                        when (rnd.nextInt(9)) {
                            0 -> fail(failures.random(rnd))
                            1 -> escalate()
                            2 -> { token("partial"); escalate() }
                            3 -> hang()
                            4 -> { token("partial"); hang() }
                            5 -> crash()
                            6 -> { token("partial"); fail(failures.random(rnd)) }
                            else -> { wait(rnd.nextInt(0, 300).milliseconds); token("answer"); done() }
                        }
                    }
                    when (rnd.nextInt(8)) {
                        0 -> probeUnreachable(failures.random(rnd))
                        1 -> probeHangs()
                    }
                }
            }
            val items = List(rnd.nextInt(0, 7)) { i ->
                val s = Sensitivity.entries.random(rnd)
                Item("<<$s-$i-${rnd.nextInt()}>>", s, label = if (rnd.nextBoolean()) "label$i" else null)
            }
            val approved = providers.filter { rnd.nextBoolean() }.map { it.id }.toSet()
            val grantedFor = mutableSetOf<ProviderId>()
            var identifierShownToApprove = false
            val approveKind = rnd.nextInt(5)
            val logger = RecordingLogger()
            val monitor = ScriptedNetworkMonitor(
                when (rnd.nextInt(5)) {
                    0 -> NetworkState.Offline
                    1 -> ScriptedNetworkMonitor.cellular()
                    else -> ScriptedNetworkMonitor.wifi("home")
                }
            )
            val shortCircuits = rnd.nextInt(12) == 0
            val ai = TieredAi {
                providers.forEach { provider(it, quality = rnd.nextInt(0, 11) / 10.0) }
                clock = SchedulerClock(testScheduler)
                network = monitor
                this.logger = logger
                when (approveKind) {
                    0 -> Unit // no approve callback at all
                    1 -> approve { _, _ -> error("approval UI crashed") }
                    else -> approve { request, provider ->
                        // Recorded, not asserted here: an exception in this callback counts as a refusal.
                        if (request.data.any { it.sensitivity == Sensitivity.Identifier }) identifierShownToApprove = true
                        (provider.id in approved).also { if (it) grantedFor += provider.id }
                    }
                }
                if (shortCircuits) shortCircuit { "verbatim" }
            }
            val request = Request(
                messages = listOf(system("be brief"), user("question")),
                data = items,
                mode = Mode.entries.random(rnd),
                rank = listOf(Ranking.AppOrder, Ranking.Quality, Ranking.Speed, Ranking.Weighted(1.0, 1.0)).random(rnd),
                strategy = listOf(
                    Strategy.Sequential, Strategy.Sequential, Strategy.Race(rnd.nextInt(1, 4)),
                    Strategy.Hedged(rnd.nextInt(0, 3000).milliseconds, rnd.nextInt(1, 4)), Strategy.FastThenBest,
                    Strategy.BestOf(rnd.nextInt(1, 4)), Strategy.BestOf(2) { rnd.nextInt(-1, 3) },
                ).random(rnd),
                pin = if (providers.isNotEmpty() && rnd.nextInt(5) == 0) providers.random(rnd).id else null,
                safeToRetry = rnd.nextBoolean(),
                idempotencyKey = if (rnd.nextInt(6) == 0) "key" else null,
                deadline = if (rnd.nextInt(6) == 0) rnd.nextInt(1, 20_000).milliseconds else null,
                needs = Needs(minContext = if (rnd.nextInt(4) == 0) 4096 else 0),
            )

            // Two requests through the same instance, so health state from the first affects the second.
            repeat(2) { round ->
                grantedFor.clear()
                val before = providers.associate { it.id to it.calls }
                val result = ai.generate(request)

                for (p in providers) {
                    for (sent in p.received.drop(before.getValue(p.id))) {
                        val text = sent.items.joinToString { it.value } +
                            sent.renderedMessages().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString { it.text }
                        assertFalse("<<Identifier" in text, "${where()}: Identifier item reached ${p.id} (${p.tier})")
                        assertTrue(sent.items.all { it.sensitivity in p.accepts }, "${where()}: ${p.id} got a sensitivity it does not accept")
                        if (p.tier != Tier.ON_DEVICE && "<<Personal" in text) {
                            personalSentWithApproval++
                            assertTrue(p.id in grantedFor, "${where()}: Personal item reached ${p.id} (${p.tier}) without approval in round $round")
                        }
                    }
                }

                val newCalls = providers.sumOf { it.calls - before.getValue(it.id) }
                when (result) {
                    is Result.Answer -> {
                        val by = providers.single { it.id == result.answeredBy }
                        assertTrue(by.calls > before.getValue(by.id), "${where()}: answeredBy names a provider that did not run")
                        assertEquals(by.tier, result.tier, where())
                        request.pin?.let { assertEquals(it, result.answeredBy, "${where()}: a pinned provider was replaced") }
                        assertFalse(shortCircuits, where())
                        answered++
                    }
                    is Result.Answered -> {
                        assertTrue(shortCircuits, where())
                        assertEquals(0, newCalls, "${where()}: a short-circuit answer reached a model")
                    }
                    is Result.Blocked -> {
                        blocked++
                        assertEquals(0, newCalls, "${where()}: Blocked but something was sent")
                    }
                    is Result.NoVerdict -> assertTrue(request.deadline != null, where())
                    is Result.NoneAvailable -> Unit
                }
                if (shortCircuits && result !is Result.Answered) fail("${where()}: short-circuit hook answered but result was $result")
                request.pin?.let { pin ->
                    assertEquals(0, providers.filter { it.id != pin }.sumOf { it.calls - before.getValue(it.id) }, "${where()}: a non-pinned provider ran")
                }
            }

            assertFalse(identifierShownToApprove, "${where()}: the approval preview contained an Identifier item")
            val logged = logger.records.joinToString("\n")
            for (marker in listOf("<<", "question", "be brief", "partial", "answer", "label")) {
                assertFalse(marker in logged, "${where()}: log contains '$marker'")
            }
        }
        // The property must not be vacuous: the dangerous and the blocked paths were all exercised.
        assertTrue(personalSentWithApproval > 100, "approved Personal sends: $personalSentWithApproval")
        assertTrue(blocked > 100, "blocked: $blocked")
        assertTrue(answered > 300, "answered: $answered")
    }

    @Test
    fun `a PreparedRequest cannot be built with an Identifier item`() {
        val error = runCatching {
            PreparedRequest(listOf(user("x")), items = listOf(Item("id", Sensitivity.Identifier)))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private companion object {
        const val SEED = 20261111
        const val CASES = 1500
    }
}
