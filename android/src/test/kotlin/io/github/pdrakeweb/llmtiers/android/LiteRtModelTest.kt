package io.github.pdrakeweb.llmtiers.android

import io.github.pdrakeweb.llmtiers.NetworkMonitor
import io.github.pdrakeweb.llmtiers.NetworkState
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.assistant
import io.github.pdrakeweb.llmtiers.system
import io.github.pdrakeweb.llmtiers.testing.FakeProvider
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LiteRtModelTest {
    @TempDir lateinit var dir: File

    private class FakeModel(private val reply: List<String> = listOf("local", " answer")) : LocalModel {
        val calls = mutableListOf<Triple<String?, List<LocalTurn>, String>>()
        @Volatile var closed = false
        @Volatile var hold: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        var failure: Exception? = null

        override fun generate(system: String?, history: List<LocalTurn>, prompt: String): Flow<String> = flow {
            calls += Triple(system, history, prompt)
            failure?.let { throw it }
            reply.forEachIndexed { i, chunk ->
                emit(chunk)
                if (i == 0) {
                    started.complete(Unit)
                    hold?.await()
                }
            }
        }

        override fun close() { closed = true }
    }

    private val hello = PreparedRequest(listOf(user("hello")))
    private fun file() = File(dir, "m.model").apply { writeText("weights") }
    private fun provider(file: File?, loader: LocalModelLoader) = LiteRtModel("gemma-text", { file }, 4096, loader, Dispatchers.Default)

    private fun bounded(block: suspend () -> Unit): Unit = runBlocking { withTimeout(20.seconds) { block() } }

    @Test
    fun `without the model file it is ModelNotReady and nothing is loaded`() = bounded {
        var loads = 0
        val p = provider(null) { loads++; FakeModel() }
        assertEquals(Reachability.Unreachable(ProviderFailure.ModelNotReady), p.probe())
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.ModelNotReady)), p.stream(hello).toList())
        assertFalse(p.warmUp())

        val gone = provider(File(dir, "deleted.model")) { loads++; FakeModel() }
        assertEquals(Reachability.Unreachable(ProviderFailure.ModelNotReady), gone.probe(), "a path whose file was deleted")
        assertEquals(0, loads)
    }

    @Test
    fun `with the file it is an on-device provider that streams, and the model is loaded once`() = bounded {
        var loads = 0
        val p = provider(file()) { loads++; FakeModel() }
        assertEquals(Tier.ON_DEVICE, p.tier)
        assertEquals(Reachability.Reachable, p.probe())
        assertEquals(0, loads, "probing does not load the model")

        val expected = listOf(ProviderEvent.Token("local"), ProviderEvent.Token(" answer"), ProviderEvent.Done(null))
        assertEquals(expected, p.stream(hello).toList())
        assertEquals(expected, p.stream(hello).toList())
        assertEquals(1, loads)
    }

    @Test
    fun `system prompt, history and the last user message are passed separately`() = bounded {
        val model = FakeModel()
        val p = provider(file()) { model }
        p.stream(PreparedRequest(listOf(system("Be brief."), user("first"), assistant("reply"), user("second")))).toList()
        val (system, history, prompt) = model.calls.single()
        assertEquals("Be brief.", system)
        assertEquals(listOf(LocalTurn(true, "first"), LocalTurn(false, "reply")), history)
        assertEquals("second", prompt)
    }

    @Test
    fun `a file that is not a usable model is ModelNotReady, and a later good file loads`() = bounded {
        var broken = true
        val p = provider(file()) { if (broken) error("not a model") else FakeModel() }
        assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.ModelNotReady)), p.stream(hello).toList())
        assertFalse(p.warmUp())
        broken = false
        assertTrue(p.warmUp())
        assertIs<ProviderEvent.Done>(p.stream(hello).toList().last())
    }

    @Test
    fun `a generation error is a typed failure that never carries the message`() = bounded {
        val model = FakeModel().apply { failure = IllegalStateException("SECRET prompt echo") }
        val events = provider(file()) { model }.stream(hello).toList()
        val failure = assertIs<ProviderFailure.Other>(assertIs<ProviderEvent.Failed>(events.single()).failure)
        assertFalse("SECRET" in failure.detail)
    }

    @Test
    fun `one request at a time - a second one meanwhile is Busy`() = bounded {
        val model = FakeModel().apply { hold = CompletableDeferred() }
        val p = provider(file()) { model }
        runBlocking {
            val first = async(Dispatchers.Default) { p.stream(hello).toList() }
            model.started.await()
            assertEquals(listOf<ProviderEvent>(ProviderEvent.Failed(ProviderFailure.Busy)), p.stream(hello).toList())
            model.hold!!.complete(Unit)
            assertIs<ProviderEvent.Done>(first.await().last())
        }
        assertIs<ProviderEvent.Done>(p.stream(hello).toList().last(), "free again afterwards")
    }

    @Test
    fun `a slow load outlives the caller that gave up, so the next request finds the model ready`() = bounded {
        val release = CountDownLatch(1)
        var loads = 0
        val p = provider(file()) { loads++; release.await(10, TimeUnit.SECONDS); FakeModel() }

        // The router's first-token timeout: the caller stops waiting while the model is still loading.
        assertNull(withTimeoutOrNull(200) { p.stream(hello).first() })
        release.countDown()
        assertTrue(p.warmUp(), "the same load completes")
        assertIs<ProviderEvent.Done>(p.stream(hello).toList().last())
        assertEquals(1, loads, "it was not started again")
    }

    @Test
    fun `close releases the model`() = bounded {
        val model = FakeModel()
        val p = provider(file()) { model }
        p.stream(hello).toList()
        p.close()
        assertTrue(model.closed)
    }

    @Test
    fun `offline on a device without Nano, the downloaded model answers`() = bounded {
        val offline = object : NetworkMonitor {
            override val state = MutableStateFlow(NetworkState.Offline)
        }
        val nano = GeminiNano(
            "gemini-nano",
            object : NanoEngine {
                override suspend fun status() = NanoStatus.Unavailable
                override suspend fun tokenLimit() = 0
                override fun generate(prompt: String, image: ByteArray?): Flow<String> = error("must not be called")
                override fun download(): Flow<Long> = flow { }
                override fun close() = Unit
            },
        )
        val cloud = FakeProvider("cloud", Tier.CLOUD)
        val ai = TieredAi {
            provider(cloud)
            provider(nano)
            provider(provider(file()) { FakeModel() })
            network = offline
        }
        val result = ai.generate(Request(listOf(user("hi")), safeToRetry = true))
        assertIs<Result.Answer>(result)
        assertEquals(ProviderId("gemma-text"), result.answeredBy)
        assertEquals("local answer", result.text)
        assertEquals(0, cloud.calls)
        assertEquals(Outcome.Failed(ProviderFailure.ModelNotReady), result.attempts.first { it.provider.value == "gemini-nano" }.outcome)
    }
}
