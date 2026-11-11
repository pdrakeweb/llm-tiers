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
    ) : Result

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

/** What [TieredAi.stream] emits. The last event is always [Completed]. */
public sealed interface Event {
    public data class Started(val provider: ProviderId, val tier: Tier) : Event
    public data class Token(val text: String) : Event

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
