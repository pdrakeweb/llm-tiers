package io.github.pdrakeweb.llmtiers.example

import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.testing.FakeProvider
import io.github.pdrakeweb.llmtiers.testing.SchedulerClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's own logic tested against the library's fakes: no network, no device, virtual time.
 * `FakeProvider` scripts what a provider does; the real router and executor run around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(vararg providers: FakeProvider, approve: Boolean = true): ChatViewModel {
        val ai = TieredAi {
            providers.forEach(::provider)
            clock = SchedulerClock(testScheduler)
            approve { _, _ -> approve }
        }
        return ChatViewModel(AiStreams(ai::stream))
    }

    @Test
    fun `shows the answer and who gave it`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider("remote", Tier.REMOTE) { tokens("Hello", " there"); done() })
        vm.send("hi")
        advanceUntilIdle()
        assertEquals("Hello there", vm.state.value.answer)
        assertEquals("Answered by remote", vm.state.value.status)
    }

    @Test
    fun `falls back and says what happened`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeProvider("remote", Tier.REMOTE) { fail(ProviderFailure.RateLimited) },
            FakeProvider("nano", Tier.ON_DEVICE) { token("from the device"); done() },
        )
        vm.send("hi")
        advanceUntilIdle()
        assertEquals("from the device", vm.state.value.answer)
        assertEquals(listOf("remote: is rate limited", "nano: answered (first word after 0 ms)"), vm.state.value.trail)
    }

    @Test
    fun `a missing key becomes advice the user can act on`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider("remote", Tier.REMOTE) { fail(ProviderFailure.KeyMissing) })
        vm.send("hi")
        advanceUntilIdle()
        assertEquals("Add an API key in settings.", vm.state.value.status)
    }

    @Test
    fun `a refused personal note is never sent, and the user is told`() = runTest(dispatcher) {
        val remote = FakeProvider("remote", Tier.REMOTE)
        val vm = viewModel(remote, approve = false)
        vm.send("hi", personalNote = "my address")
        advanceUntilIdle()
        assertEquals(0, remote.calls)
        assertTrue(vm.state.value.status.startsWith("Not sent"))
    }
}
