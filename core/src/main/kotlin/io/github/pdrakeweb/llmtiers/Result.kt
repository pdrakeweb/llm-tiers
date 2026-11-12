package io.github.pdrakeweb.llmtiers

import kotlin.time.Duration

/** How every request ends. Each variant says where the request ran, or why it did not. */
public sealed interface Result {
    /**
     * A model answered.
     *
     * @property discardPartial true when an earlier provider streamed partial output and then
     *   escalated; the app must replace that partial output with [text], not append to it.
     * @property incomplete non-null when the provider failed after its first token: [text] is
     *   what was received and this is why it stopped. The request is not moved to another
     *   provider once a token has streamed.
     */
    public data class Answer(
        val text: String,
        val answeredBy: ProviderId,
        val tier: Tier,
        val attempts: List<Attempt>,
        val discardPartial: Boolean,
        val incomplete: ProviderFailure? = null,
    ) : Result {
        /** Content-free, so an accidental `"$result"` in app logging does not leak the answer. */
        override fun toString(): String =
            "Answer(${text.length} chars, answeredBy=$answeredBy, tier=$tier, discardPartial=$discardPartial, incomplete=$incomplete, attempts=$attempts)"
    }

    /** The app's short-circuit hook answered. No model ran. */
    public data class Answered(val shortCircuit: Any) : Result

    /** Privacy rules left no provider the request was allowed to reach. Nothing was sent. */
    public data class Blocked(val reason: String) : Result

    /** The request's hard deadline passed before an answer completed. */
    public data class NoVerdict(val attempts: List<Attempt>) : Result

    /** No provider could answer. [attempts] says what was tried or skipped, and why. */
    public data class NoneAvailable(val attempts: List<Attempt>) : Result
}

/** One provider considered for a request: tried, or skipped with a reason. */
public data class Attempt(
    val provider: ProviderId,
    val tier: Tier,
    val outcome: Outcome,
    /** Why the policy placed this provider where it did (for example "preferred tier"). */
    val why: String = "",
    val elapsed: Duration = Duration.ZERO,
    val firstTokenAfter: Duration? = null,
    val usage: Usage? = null,
)

public sealed interface Outcome {
    public data object Succeeded : Outcome
    public data class Failed(val failure: ProviderFailure) : Outcome
    public data object Escalated : Outcome

    /** The provider was started but another one was used instead; it was cancelled. */
    public data object Superseded : Outcome
    public data class Skipped(val reason: SkipReason) : Outcome
}

/** Why a provider was not tried. [code] is the stable form used in the policy test vectors. */
public sealed interface SkipReason {
    public val code: String

    /** True when the skip is a privacy decision (invariant A-1). */
    public val privacy: Boolean get() = false

    public data class SensitivityNotAccepted(val sensitivity: Sensitivity) : SkipReason {
        override val code: String get() = "SensitivityNotAccepted:$sensitivity"
        override val privacy: Boolean get() = true
    }

    public data object ApprovalDenied : SkipReason {
        override val code: String get() = "ApprovalDenied"
        override val privacy: Boolean get() = true
    }

    /** [capability] is `vision`, `jsonSchema`, `text` or `context`. */
    public data class MissingCapability(val capability: String) : SkipReason {
        override val code: String get() = "MissingCapability:$capability"
    }

    public data class ExcludedByMode(val mode: Mode) : SkipReason {
        override val code: String get() = "ExcludedByMode:$mode"
    }

    /** The user pinned a different provider. */
    public data object NotPinned : SkipReason {
        override val code: String get() = "NotPinned"
    }

    public data object CoolingDown : SkipReason {
        override val code: String get() = "CoolingDown"
    }

    public data object Offline : SkipReason {
        override val code: String get() = "Offline"
    }

    /** An earlier provider may have processed the request and it is not marked safe to retry. */
    public data object NotSafeToRetry : SkipReason {
        override val code: String get() = "NotSafeToRetry"
    }

    /** The provider did not report its capabilities in time. */
    public data object CapabilitiesUnavailable : SkipReason {
        override val code: String get() = "CapabilitiesUnavailable"
    }
}

/**
 * What [TieredAi.stream] emits. The last event is always [Completed].
 *
 * Tokens shown to the user are provisional until `Completed`: clear them on a `FellBack` with
 * `discardPartial`, and whenever the final result is not an [Result.Answer] (for example a
 * provider streamed, escalated, and nothing else could answer).
 */
public sealed interface Event {
    public data class Started(val provider: ProviderId, val tier: Tier) : Event
    public data class Token(val text: String) : Event {
        override fun toString(): String = "Token(${text.length} chars)"
    }

    /**
     * The provider is reasoning before it answers. Emitted once per attempt, before any token
     * from it, so an app can show "thinking" instead of an empty wait.
     */
    public data class Thinking(val provider: ProviderId) : Event

    /**
     * The request moved from one provider to the next.
     * @property discardPartial true when [from] had already streamed tokens (it escalated); the
     *   app must clear them.
     */
    public data class FellBack(
        val from: ProviderId,
        val to: ProviderId,
        val reason: Outcome,
        val discardPartial: Boolean,
    ) : Event

    /**
     * A quick answer from [from] is on screen and the better answer from [to] is ready: clear the
     * text and show what follows. Used by [Strategy.FastThenBest].
     */
    public data class Upgrading(val from: ProviderId, val to: ProviderId) : Event

    public data class Completed(val result: Result) : Event
}

/** One content-free log line: provider ids, timings and failure types only. */
public data class LogRecord(
    val event: String,
    val provider: ProviderId? = null,
    val tier: Tier? = null,
    val outcome: String? = null,
    val elapsedMillis: Long? = null,
    val firstTokenMillis: Long? = null,
)

public fun interface Logger {
    public fun log(record: LogRecord)
}
