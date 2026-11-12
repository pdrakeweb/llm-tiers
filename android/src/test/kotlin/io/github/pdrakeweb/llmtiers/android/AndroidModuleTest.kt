package io.github.pdrakeweb.llmtiers.android

import io.github.pdrakeweb.llmtiers.Item
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.assistant
import io.github.pdrakeweb.llmtiers.system
import io.github.pdrakeweb.llmtiers.testing.FakeProvider
import io.github.pdrakeweb.llmtiers.testing.SchedulerClock
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrivateAddressesTest {
    private fun ip(literal: String) = InetAddress.getByName(literal) // literals only: no DNS lookups

    @Test
    fun `loopback, link-local, RFC 1918, CGNAT and unique-local addresses are private`() {
        val private = listOf(
            "127.0.0.1", "127.8.9.10", "::1",
            "10.0.0.1", "10.0.2.2", "10.255.255.255",
            "172.16.0.1", "172.31.255.254",
            "192.168.0.1", "192.168.1.20",
            "169.254.10.10", "fe80::1",
            "100.64.0.1", "100.100.100.100", "100.127.255.255",
            "fc00::1", "fd12:3456:789a::1",
            "::ffff:192.168.1.20",
        )
        for (a in private) assertTrue(PrivateAddresses.isPrivate(ip(a)), a)
    }

    @Test
    fun `public addresses are not private, including the ones just outside each range`() {
        val public = listOf(
            "8.8.8.8", "1.1.1.1", "9.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0",
            "100.63.255.255", "100.128.0.0",
            "169.253.255.255", "169.255.0.0",
            "2001:4860:4860::8888", "fe00::1", "::ffff:8.8.8.8",
            "0.0.0.0", "224.0.0.251", "ff02::fb",
        )
        for (a in public) assertFalse(PrivateAddresses.isPrivate(ip(a)), a)
    }

    @Test
    fun `plain HTTP is allowed only to a private address or a dot-local name, and never through a proxy`() {
        assertTrue(PrivateAddresses.allowsCleartext("192.168.1.20", ip("192.168.1.20"), direct = true))
        assertTrue(PrivateAddresses.allowsCleartext("localhost", ip("127.0.0.1"), direct = true))
        assertTrue(PrivateAddresses.allowsCleartext("llama.local", ip("2001:db8::5"), direct = true), "an mDNS host may have a global IPv6 address")
        assertTrue(PrivateAddresses.allowsCleartext("LLAMA.LOCAL.", ip("192.168.1.20"), direct = true))

        assertFalse(PrivateAddresses.allowsCleartext("api.example.com", ip("93.184.216.34"), direct = true))
        assertFalse(PrivateAddresses.allowsCleartext("home.example.com", ip("93.184.216.34"), direct = true), "a name that resolves somewhere public")
        assertFalse(PrivateAddresses.allowsCleartext("notlocal.example", ip("8.8.8.8"), direct = true))
        assertFalse(PrivateAddresses.allowsCleartext("evil-local", ip("8.8.8.8"), direct = true))
        assertFalse(PrivateAddresses.allowsCleartext("192.168.1.20", null, direct = true), "unknown address fails closed")
        assertFalse(PrivateAddresses.allowsCleartext("192.168.1.20", ip("192.168.1.20"), direct = false), "a proxy hides the real destination")
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalNetworkGateTest {
    private val request = PreparedRequest(listOf(user("hi")))

    @Test
    fun `without the permission the provider reports LocalNetworkPermissionDenied and is never called`() = runTest {
        val lan = FakeProvider("home", Tier.LAN)
        val gated = LocalNetworkGate(lan) { true }

        assertEquals(Reachability.Unreachable(ProviderFailure.LocalNetworkPermissionDenied), gated.probe())
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.LocalNetworkPermissionDenied)), gated.stream(request).toList())
        assertEquals(0, lan.calls + lan.probes)
        assertEquals(lan.id, gated.id)
        assertEquals(Tier.LAN, gated.tier)
    }

    @Test
    fun `the router falls through cleanly and switches back as soon as the permission is granted`() = runTest {
        var missing = true
        val lan = FakeProvider("home", Tier.LAN) { token("lan"); done() }
        val cloud = FakeProvider("cloud", Tier.CLOUD) { token("cloud"); done() }
        val ai = TieredAi {
            provider(LocalNetworkGate(lan) { missing })
            provider(cloud)
            clock = SchedulerClock(testScheduler)
        }

        val denied = ai.generate(Request(listOf(user("hi"))))
        assertIs<Result.Answer>(denied)
        assertEquals(ProviderId("cloud"), denied.answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.LocalNetworkPermissionDenied), denied.attempts.first().outcome)
        assertEquals(0L, currentTime, "no timeout was waited for")

        missing = false
        ai.health.reset(ProviderId("home")) // what an app does after the user grants the permission
        assertEquals(ProviderId("home"), (ai.generate(Request(listOf(user("hi")))) as Result.Answer).answeredBy)
    }
}

class GeminiNanoTest {
    private class FakeEngine(
        var status: NanoStatus = NanoStatus.Available,
        var limit: Int = 4096,
        var chunks: List<String> = listOf("Hel", "", "lo"),
        var failure: ProviderFailure? = null,
        var failAfter: Int = 0,
    ) : NanoEngine {
        val prompts = mutableListOf<String>()
        val images = mutableListOf<ByteArray?>()
        override suspend fun status() = status
        override suspend fun tokenLimit() = limit
        override fun generate(prompt: String, image: ByteArray?): Flow<String> = flow {
            prompts += prompt
            images += image
            chunks.forEachIndexed { i, c ->
                if (failure != null && i == failAfter) throw NanoException(failure!!)
                emit(c)
            }
            if (failure != null && failAfter >= chunks.size) throw NanoException(failure!!)
        }
        override fun download(): Flow<Long> = flow { emit(10); emit(20) }
        override fun close() = Unit
    }

    private val hello = PreparedRequest(listOf(user("hello")))

    @Test
    fun `it is an on-device provider with a small context and no JSON-schema support`() = runTest {
        val nano = GeminiNano("gemini-nano", FakeEngine(limit = 4096))
        assertEquals(Tier.ON_DEVICE, nano.tier)
        assertEquals(4000, nano.capabilities().maxContext, "a safe default before the model has been asked")
        assertEquals(Reachability.Reachable, nano.probe())
        assertEquals(4096, nano.capabilities().maxContext, "the model's own limit once known")
        assertFalse(nano.capabilities().jsonSchema)
    }

    @Test
    fun `any status but Available is ModelNotReady`() = runTest {
        for (status in listOf(NanoStatus.Unavailable, NanoStatus.Downloadable, NanoStatus.Downloading)) {
            assertEquals(Reachability.Unreachable(ProviderFailure.ModelNotReady), GeminiNano("n", FakeEngine(status)).probe(), "$status")
        }
    }

    @Test
    fun `streams chunks as tokens and ends with Done`() = runTest {
        val events = GeminiNano("n", FakeEngine()).stream(hello).toList()
        assertEquals(listOf(ProviderEvent.Token("Hel"), ProviderEvent.Token("lo"), ProviderEvent.Done(null)), events)
    }

    @Test
    fun `failures are typed, before or after the first chunk`() = runTest {
        val busy = GeminiNano("n", FakeEngine(failure = ProviderFailure.Busy)).stream(hello).toList()
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.Busy)), busy)

        val midway = GeminiNano("n", FakeEngine(failure = ProviderFailure.Other("genai 4"), failAfter = 1)).stream(hello).toList()
        assertEquals(listOf(ProviderEvent.Token("Hel"), ProviderEvent.Failed(ProviderFailure.Other("genai 4"))), midway)
    }

    @Test
    fun `a too-large request reports this model's context limit so the router picks a bigger one`() = runTest {
        val nano = GeminiNano("n", FakeEngine(limit = 4096, failure = ProviderFailure.ContextTooLong(0)))
        nano.probe()
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.ContextTooLong(4096))), nano.stream(hello).toList())
    }

    @Test
    fun `the conversation, system prompt and data items are flattened into one prompt`() = runTest {
        val engine = FakeEngine()
        val nano = GeminiNano("n", engine)
        nano.stream(PreparedRequest(listOf(system("Be brief."), user("hello")))).toList()
        assertEquals("Be brief.\n\nhello", engine.prompts.last())

        nano.stream(
            PreparedRequest(
                listOf(user("first"), assistant("reply"), user("second")),
                items = listOf(Item("30 minutes", Sensitivity.Public, "limit")),
            )
        ).toList()
        assertEquals("User: first\nAssistant: reply\nUser: Context:\n- limit: 30 minutes\n\nsecond\nAssistant:", engine.prompts.last())
    }

    @Test
    fun `ML Kit error codes map to typed failures`() {
        assertEquals(ProviderFailure.Busy, genAiFailure(9), "BUSY: quota or background use")
        assertEquals(ProviderFailure.Busy, genAiFailure(30), "BACKGROUND_USE_BLOCKED")
        assertEquals(ProviderFailure.Busy, genAiFailure(27), "PER_APP_BATTERY_USE_QUOTA_EXCEEDED")
        assertEquals(ProviderFailure.ModelNotReady, genAiFailure(8), "NOT_AVAILABLE")
        assertEquals(ProviderFailure.ModelNotReady, genAiFailure(604), "NEEDS_SYSTEM_UPDATE")
        assertIs<ProviderFailure.ContextTooLong>(genAiFailure(12), "REQUEST_TOO_LARGE")
        assertEquals(ProviderFailure.Other("genai 4"), genAiFailure(4))
    }

    @Test
    fun `in the router - Nano not ready falls through, Nano busy cools down briefly`() = runTest {
        val engine = FakeEngine(status = NanoStatus.Downloadable)
        val cloud = FakeProvider("cloud", Tier.CLOUD) { token("cloud"); done() }
        val ai = TieredAi {
            provider(GeminiNano("gemini-nano", engine))
            provider(cloud)
            clock = SchedulerClock(testScheduler)
        }
        val result = ai.generate(Request(listOf(user("hi"))))
        assertEquals(ProviderId("cloud"), (result as Result.Answer).answeredBy)
        assertEquals(Outcome.Failed(ProviderFailure.ModelNotReady), result.attempts.first().outcome)
        assertTrue(engine.prompts.isEmpty(), "the prompt was never given to a model that is not ready")
    }
}
