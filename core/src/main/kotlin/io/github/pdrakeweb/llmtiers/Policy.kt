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
 *
 * `Identifier` items are ignored here exactly as if they were absent; the executor removes them
 * before any provider sees the request.
 */
public fun plan(
    request: Request,
    providers: List<ProviderInfo>,
    health: HealthSnapshot,
    network: NetworkState,
): Plan {
    val sensitivities = request.data.map { it.sensitivity }.filter { it != Sensitivity.Identifier }.toSet()
    val hasPersonal = Sensitivity.Personal in sensitivities
    val preferred = preferredTier(request.mode)
    val only = onlyTier(request.mode)

    val skipped = mutableListOf<Skipped>()
    val kept = mutableListOf<ProviderInfo>()
    for (p in providers) {
        val reason = skipReason(request, p, sensitivities, only, health, network)
        if (reason != null) skipped += Skipped(p.id, p.tier, reason) else kept += p
    }

    // Ranking, then mode. Both are stable sorts, so ties keep the app's order and the preferred
    // tier stays in front with the ranking applied inside each group.
    val ranked = when (val rank = request.rank) {
        Ranking.AppOrder -> kept
        else -> kept.sortedByDescending { score(rank, it, health) }
    }
    val ordered = if (preferred != null && request.pin == null) ranked.sortedBy { if (it.tier == preferred) 0 else 1 } else ranked

    var steps = ordered.map { p ->
        Step(
            provider = p,
            needsApproval = hasPersonal && p.tier != Tier.ON_DEVICE,
            reason = when {
                request.pin != null -> "pinned by the user"
                preferred != null && p.tier == preferred -> "preferred tier (${request.mode})"
                only != null -> "only tier allowed (${request.mode})"
                request.rank == Ranking.Quality -> "ranked by quality"
                request.rank == Ranking.Speed -> "ranked by speed"
                request.rank is Ranking.Weighted -> "ranked by quality and speed"
                else -> "app order"
            },
        )
    }

    // Budget: if the first choice is known to be too slow, move the first provider known to be
    // fast enough to the front. Unknown latency is never treated as either.
    val budget = request.budget?.firstToken
    if (budget != null && steps.size > 1) {
        val firstTypical = health[steps.first().provider.id].typicalFirstToken
        if (firstTypical != null && firstTypical > budget) {
            val index = steps.indexOfFirst { s -> health[s.provider.id].typicalFirstToken?.let { it <= budget } == true }
            if (index > 0) {
                val moved = steps[index].copy(reason = "moved to the front: meets the latency budget")
                steps = listOf(moved) + steps.filterIndexed { i, _ -> i != index }
            }
        }
    }
    return Plan(steps, skipped)
}

private fun preferredTier(mode: Mode): Tier? = when (mode) {
    Mode.PreferOnDevice -> Tier.ON_DEVICE
    Mode.PreferRemote -> Tier.REMOTE
    Mode.AppOrder, Mode.OnlyOnDevice, Mode.OnlyRemote -> null
}

private fun onlyTier(mode: Mode): Tier? = when (mode) {
    Mode.OnlyOnDevice -> Tier.ON_DEVICE
    Mode.OnlyRemote -> Tier.REMOTE
    Mode.AppOrder, Mode.PreferOnDevice, Mode.PreferRemote -> null
}

/** Seconds to first answer text: learned when known, otherwise a typical figure for the tier. */
private fun latencySeconds(p: ProviderInfo, health: HealthSnapshot): Double =
    health[p.id].typicalFirstToken?.let { it.inWholeMilliseconds / 1000.0 } ?: when (p.tier) {
        Tier.ON_DEVICE -> 1.0
        Tier.REMOTE -> 2.0
    }

private fun score(rank: Ranking, p: ProviderInfo, health: HealthSnapshot): Double = when (rank) {
    Ranking.AppOrder -> 0.0
    Ranking.Quality -> p.quality
    Ranking.Speed -> -latencySeconds(p, health)
    is Ranking.Weighted -> rank.quality * p.quality + rank.speed / (1.0 + latencySeconds(p, health))
}

/** The first rule that excludes [p], in rule order, or null when it stays in the plan. */
private fun skipReason(
    request: Request,
    p: ProviderInfo,
    sensitivities: Set<Sensitivity>,
    only: Tier?,
    health: HealthSnapshot,
    network: NetworkState,
): SkipReason? {
    val pinned = request.pin != null

    // 1. Privacy.
    Sensitivity.entries.firstOrNull { it in sensitivities && it !in p.accepts }
        ?.let { return SkipReason.SensitivityNotAccepted(it) }

    // 2. Capability. A pinned provider is checked like any other; the others are simply not it.
    if (!pinned || request.pin == p.id) {
        val caps = p.capabilities
        when {
            !caps.text -> return SkipReason.MissingCapability("text")
            request.needsVision && !caps.vision -> return SkipReason.MissingCapability("vision")
            request.needs.structuredJson != null && !caps.jsonSchema -> return SkipReason.MissingCapability("jsonSchema")
            request.needs.minContext > caps.maxContext -> return SkipReason.MissingCapability("context")
        }
    }

    // 3. Mode and pinning. A pin is the user's explicit choice and overrides the mode.
    if (pinned) {
        if (request.pin != p.id) return SkipReason.NotPinned
    } else if (only != null && p.tier != only) {
        return SkipReason.ExcludedByMode(request.mode)
    }

    // 4. Health. Offline is certain; a cooldown is ignored for a pinned provider (the user chose it).
    if (!network.online && p.tier != Tier.ON_DEVICE) return SkipReason.Offline
    if (!pinned && health[p.id].state == HealthState.Out) return SkipReason.CoolingDown
    return null
}
