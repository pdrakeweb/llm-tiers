package io.github.pdrakeweb.llmtiers

/** One provider the executor should try, in order. */
public data class Step(
    val provider: ProviderInfo,
    /** The request carries `Personal` items and this provider is off-device: ask the app first. */
    val needsApproval: Boolean,
    /** Why the provider sits at this position. */
    val reason: String,
)

public data class Skipped(val provider: ProviderId, val tier: Tier, val reason: SkipReason)

public data class Plan(val steps: List<Step>, val skipped: List<Skipped>)

/**
 * The routing policy: a pure, synchronous function (ADR-0001).
 *
 * Rules, in order: privacy, capability, mode and pinning, health, budget. [providers] is the app's
 * configured order. Documented by the JSON vectors in the `:testing` module.
 */
public fun plan(
    request: Request,
    providers: List<ProviderInfo>,
    health: HealthSnapshot,
    network: NetworkState,
): Plan = TODO("T1: policy")
