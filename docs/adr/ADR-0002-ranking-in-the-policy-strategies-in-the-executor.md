# ADR-0002 — Ranking belongs to the policy, strategies to the executor; two tiers

**Status:** accepted (Pete, 11 Nov 2026: "Figure out the main possible/probably algorithms and
implement them", and "we should just offer ondevice (multiple?) and remote (multiple)").

**Context.** ADR-0001 made routing a pure function and put failover in the executor. That gave
one behaviour: an ordered chain tried one at a time. Developers want more: best provider first,
fastest first, whoever answers first, a quick on-device answer upgraded by a better remote one,
the best of several. Separately, the three tiers (on-device, LAN, cloud) drew a line between a
home server and a cloud API that the protocol does not have.

**Decision.**

1. *Which providers and in what order* stays in the pure `plan()`: `mode`, `pin`, and a new
   `rank` (`AppOrder`, `Quality`, `Speed`, `Weighted`). It is documented by the JSON vectors like
   every other rule.
2. *How the plan is run* is a new `strategy` handled by the executor: `Sequential`, `Race`,
   `Hedged`, `FastThenBest`, `BestOf`. Every concurrent strategy reuses the same admission
   checks (retry safety, approval, probe) and the same per-attempt timeouts as the sequential
   path, so the three invariants hold by construction rather than by re-implementation.
3. A concurrent strategy is a replay, so it applies only to requests marked safe to retry.
4. Tiers are `ON_DEVICE` and `REMOTE`. What used to distinguish LAN from cloud becomes
   per-provider: `probeBeforeUse`, and the address-based rules in `:android`.

**Consequences.**

- New public types: `Ranking`, `Strategy`, `Judge`, `Candidate`, `Outcome.Superseded`,
  `Event.Upgrading`, `ProviderInfo.quality`, `Provider.probeBeforeUse`. `Tier.LAN`, `Tier.CLOUD`
  and the `PreferLan` / `PreferCloud` / `OnlyCloud` modes are gone. Nothing was published, so
  nothing needs migrating.
- "Prefer my own server" is no longer a mode. It is expressed by the configured order, a pin, or
  a quality weight.
- Concurrent strategies cost more requests. `Hedged` is the cheap one: it costs nothing extra
  while the first provider is healthy.
- `FastThenBest` replaces the draft only when the remote answer is complete. Streaming the
  better answer over the draft was rejected: a remote failure half way would leave the user with
  half an answer and no draft.
- Policy vectors moved to format 2.
