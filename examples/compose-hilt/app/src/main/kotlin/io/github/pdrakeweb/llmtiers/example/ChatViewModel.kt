package io.github.pdrakeweb.llmtiers.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pdrakeweb.llmtiers.Attempt
import io.github.pdrakeweb.llmtiers.Event
import io.github.pdrakeweb.llmtiers.Item
import io.github.pdrakeweb.llmtiers.Mode
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.SkipReason
import io.github.pdrakeweb.llmtiers.Strategy
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatState(
    val answer: String = "",
    /** One line for the user: who answered, or why nobody did. */
    val status: String = "",
    val thinking: Boolean = false,
    val running: Boolean = false,
    /** What was tried, in order, for a "details" view. */
    val trail: List<String> = emptyList(),
)

@HiltViewModel
class ChatViewModel @Inject constructor(private val ai: AiStreams) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state
    private var job: Job? = null

    /**
     * @param personalNote text the user may not want to leave the device. It is tagged `Personal`,
     *   so the library asks for approval before any remote provider sees it.
     */
    fun send(prompt: String, personalNote: String = "", mode: Mode = Mode.AppOrder, strategy: Strategy = Strategy.Sequential) {
        job?.cancel() // a new question replaces the one in flight; the library cancels the provider
        _state.value = ChatState(running = true)
        val request = Request(
            messages = listOf(user(prompt)),
            data = if (personalNote.isBlank()) emptyList() else listOf(Item(personalNote, Sensitivity.Personal, "note")),
            mode = mode,
            strategy = strategy,
            // Generating text has no side effects, so it is safe to send to a second provider
            // after a timeout. Leave this false for a request that must not run twice.
            safeToRetry = true,
        )
        job = viewModelScope.launch {
            ai.stream(request).collect { event ->
                when (event) {
                    is Event.Started -> Unit
                    is Event.Thinking -> _state.update { it.copy(thinking = true) }
                    is Event.Token -> _state.update { it.copy(answer = it.answer + event.text, thinking = false) }
                    // The text on screen came from a provider that was then dropped: clear it.
                    is Event.FellBack -> if (event.discardPartial) _state.update { it.copy(answer = "") }
                    is Event.Upgrading -> _state.update { it.copy(answer = "") }
                    is Event.Completed -> _state.value = finished(event.result)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false, thinking = false, status = "Stopped") }
    }

    /** Every result is handled: the `when` is exhaustive, so a new result type will not compile unnoticed. */
    private fun finished(result: Result): ChatState = when (result) {
        is Result.Answer -> ChatState(
            // The result's text is the answer of record; tokens were only a preview of it.
            answer = result.text,
            status = "Answered by ${result.answeredBy}" + (result.incomplete?.let { ", but it was cut short (${explain(it)})" } ?: ""),
            trail = result.attempts.map(::describe),
        )
        is Result.Answered -> ChatState(answer = result.shortCircuit.toString(), status = "Answered by the app itself")
        is Result.Blocked -> ChatState(status = "Not sent: nothing was allowed to receive your personal note.")
        is Result.NoVerdict -> ChatState(status = "No answer in time.", trail = result.attempts.map(::describe))
        is Result.NoneAvailable -> ChatState(
            status = result.attempts.firstNotNullOfOrNull(::advice) ?: "No AI is available right now.",
            trail = result.attempts.map(::describe),
        )
    }

    /** The first thing the user can act on, if any. */
    private fun advice(attempt: Attempt): String? = when (val outcome = attempt.outcome) {
        is Outcome.Failed -> when (outcome.failure) {
            ProviderFailure.KeyMissing -> "Add an API key in settings."
            ProviderFailure.AuthRejected -> "The API key was rejected. Check it in settings."
            ProviderFailure.LocalNetworkPermissionDenied -> "Allow access to devices on your network to use your own server."
            ProviderFailure.RateLimited -> "The service is busy. Try again in a moment."
            else -> null
        }
        is Outcome.Skipped -> if (outcome.reason == SkipReason.Offline) "You are offline and no on-device model is ready." else null
        else -> null
    }

    private fun describe(attempt: Attempt): String = "${attempt.provider}: " + when (val outcome = attempt.outcome) {
        Outcome.Succeeded -> "answered" + (attempt.firstTokenAfter?.let { " (first word after ${it.inWholeMilliseconds} ms)" } ?: "")
        Outcome.Escalated -> "passed it on"
        Outcome.Superseded -> "started, then not needed"
        is Outcome.Failed -> explain(outcome.failure)
        is Outcome.Skipped -> "skipped (${outcome.reason.code})"
    }

    /** Exhaustive on purpose: every failure type has words a person can read. */
    private fun explain(failure: ProviderFailure): String = when (failure) {
        ProviderFailure.Unreachable -> "could not be reached"
        ProviderFailure.Timeout, ProviderFailure.FirstTokenTimeout -> "took too long to start"
        ProviderFailure.Stalled -> "stopped responding"
        ProviderFailure.AuthRejected -> "rejected the API key"
        ProviderFailure.KeyMissing -> "has no API key"
        ProviderFailure.RateLimited -> "is rate limited"
        is ProviderFailure.ContextTooLong -> "cannot take a prompt this long"
        is ProviderFailure.Server -> "had a server error (${failure.code})"
        ProviderFailure.ModelNotReady -> "has no model ready"
        ProviderFailure.Busy -> "is busy"
        ProviderFailure.LocalNetworkPermissionDenied -> "needs permission to reach devices on your network"
        is ProviderFailure.Other -> "failed (${failure.detail})"
    }
}
