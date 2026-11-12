package io.github.pdrakeweb.llmtiers.openai

import io.github.pdrakeweb.llmtiers.Event
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.TimeoutConfig
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Contract tests against real OpenAI-compatible endpoints. Opt-in: each test is skipped unless its
 * environment variables are set (see docs/CONTRACT_TESTS.md). Keys are read from the environment
 * only and are never printed.
 */
class ContractTest {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test
    fun `LAN endpoint (LiteLLM or llama_cpp) streams an answer`() {
        val baseUrl = env("LLM_TIERS_LAN_BASE_URL")
        val model = env("LLM_TIERS_LAN_MODEL")
        assumeTrue(baseUrl != null && model != null, "set LLM_TIERS_LAN_BASE_URL and LLM_TIERS_LAN_MODEL to run")
        streamsAnAnswer(OpenAiCompatible("lan", baseUrl!!, model!!, Tier.LAN, apiKey = env("LLM_TIERS_LAN_API_KEY"), maxTokens = 1024))
    }

    @Test
    fun `LAN endpoint streams a longer answer in several chunks`() {
        val baseUrl = env("LLM_TIERS_LAN_BASE_URL")
        val model = env("LLM_TIERS_LAN_MODEL")
        assumeTrue(baseUrl != null && model != null, "set LLM_TIERS_LAN_BASE_URL and LLM_TIERS_LAN_MODEL to run")
        streamsALongerAnswer(OpenAiCompatible("lan", baseUrl!!, model!!, Tier.LAN, apiKey = env("LLM_TIERS_LAN_API_KEY"), maxTokens = 1024))
    }

    @Test
    fun `OpenRouter streams a longer answer in several chunks`() {
        val key = env("LLM_TIERS_OPENROUTER_API_KEY")
        val model = env("LLM_TIERS_OPENROUTER_MODEL")
        assumeTrue(key != null && model != null, "set LLM_TIERS_OPENROUTER_API_KEY and LLM_TIERS_OPENROUTER_MODEL to run")
        streamsALongerAnswer(OpenAiCompatible("openrouter", "https://openrouter.ai/api/v1", model!!, Tier.CLOUD, apiKey = key, maxTokens = 1024))
    }

    @Test
    fun `OpenRouter streams an answer`() {
        val key = env("LLM_TIERS_OPENROUTER_API_KEY")
        val model = env("LLM_TIERS_OPENROUTER_MODEL")
        assumeTrue(key != null && model != null, "set LLM_TIERS_OPENROUTER_API_KEY and LLM_TIERS_OPENROUTER_MODEL to run")
        streamsAnAnswer(OpenAiCompatible("openrouter", "https://openrouter.ai/api/v1", model!!, Tier.CLOUD, apiKey = key, maxTokens = 1024))
    }

    @Test
    fun `OpenRouter rejects a wrong key as AuthRejected`() {
        assumeTrue(env("LLM_TIERS_OPENROUTER_API_KEY") != null, "set LLM_TIERS_OPENROUTER_API_KEY to run")
        val provider = OpenAiCompatible(
            "openrouter", "https://openrouter.ai/api/v1", env("LLM_TIERS_OPENROUTER_MODEL") ?: "openrouter/auto",
            Tier.CLOUD, apiKey = "sk-or-v1-deliberately-invalid",
        )
        val result = runBlocking { withTimeout(60.seconds) { TieredAi { provider(provider) }.generate(Request(listOf(user("hi")))) } }
        assertIs<Result.NoneAvailable>(result)
        assertEquals(Outcome.Failed(ProviderFailure.AuthRejected), result.attempts.single().outcome)
    }

    private fun streamsAnAnswer(provider: OpenAiCompatible) = runBlocking {
        withTimeout(120.seconds) {
            assertEquals(Reachability.Reachable, provider.probe(), "probe (GET /models)")
            val ai = TieredAi {
                provider(provider)
                timeouts = TimeoutConfig(firstToken = 60.seconds, stall = 30.seconds) // cold model loads are slow
            }
            val events = ai.stream(Request(listOf(user("Reply with the single word: pong")))).toList()
            val result = (events.last() as Event.Completed).result
            assertIs<Result.Answer>(result, "result was $result")
            assertNull(result.incomplete)
            assertEquals(provider.id, result.answeredBy)
            val tokens = events.count { it is Event.Token }
            assertTrue(tokens >= 1 && result.text.isNotBlank(), "expected streamed tokens")
            val attempt = result.attempts.single()
            println("contract: ${provider.id} answered with $tokens token event(s), first token after ${attempt.firstTokenAfter}, total ${attempt.elapsed}")
        }
    }

    /**
     * A one-word reply can arrive as a single chunk, which proves an answer but not streaming. A
     * longer reply must arrive in several chunks, spread over time. A reasoning model that streams
     * only its reasoning for longer than the first-token timeout fails here with FirstTokenTimeout.
     */
    private fun streamsALongerAnswer(provider: OpenAiCompatible) = runBlocking {
        withTimeout(180.seconds) {
            val ai = TieredAi {
                provider(provider)
                timeouts = TimeoutConfig(firstToken = 60.seconds, stall = 30.seconds)
            }
            val longer = ai.stream(Request(listOf(user("Write the numbers from one to thirty as English words, separated by commas.")))).toList()
            val longResult = (longer.last() as Event.Completed).result
            assertIs<Result.Answer>(longResult, "result was $longResult")
            val chunks = longer.count { it is Event.Token }
            val run = longResult.attempts.single()
            println("contract: ${provider.id} streamed a longer answer in $chunks token event(s), first token after ${run.firstTokenAfter}, total ${run.elapsed}")
            assertTrue(chunks >= 5, "expected the longer answer in at least 5 chunks, got $chunks")
            assertTrue(run.firstTokenAfter!! < run.elapsed, "the first token arrived before the answer finished")
        }
    }
}
