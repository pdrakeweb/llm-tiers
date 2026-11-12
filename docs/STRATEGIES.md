# Routing strategies

How a developer tells `llm-tiers` which providers to use, in what order, and how to run them.
There are four independent dials on a request, plus one automatic behaviour.

| Dial | Question it answers | Decided by |
|---|---|---|
| `mode` | On the device, remote, or either? | Pure policy |
| `pin` | Exactly this one provider? | Pure policy |
| `rank` | Among those allowed, who goes first? | Pure policy |
| `strategy` | One at a time, or several at once? | Executor |

Providers are either **on-device** or **remote** (D-033). A home server, a self-hosted gateway and
a commercial API are all remote: they speak the same protocol, and what differs between them is a
property of each provider, not a tier.

## What you want, and how to say it

| Intent | Request |
|---|---|
| Use my configured order, fall back down it | defaults (`Strategy.Sequential`, `Ranking.AppOrder`) |
| Cloud first, on-device only if needed | `mode = Mode.PreferRemote` |
| On-device first, remote only if needed | `mode = Mode.PreferOnDevice` |
| The user chose "on device only" | `mode = Mode.OnlyOnDevice` |
| The user chose "remote only" | `mode = Mode.OnlyRemote` |
| The user chose one particular provider (their server, a given account, a given model) | `pin = ProviderId("home")` |
| Airplane mode or no signal: use what is on the device | nothing to set (see below) |
| Best answer first | `rank = Ranking.Quality` |
| Fastest first, from what the library has measured | `rank = Ranking.Speed` |
| A blend of quality and speed | `rank = Ranking.Weighted(quality = 1.0, speed = 0.5)` |
| Whoever answers first | `strategy = Strategy.Race()` |
| Start a second provider only if the first is slow | `strategy = Strategy.Hedged(after = 2.seconds)` |
| A quick on-device answer now, replaced by a better remote one | `strategy = Strategy.FastThenBest` |
| Ask several, keep the best answer | `strategy = Strategy.BestOf(2)` or `BestOf(2, judge)` |
| A tiny job that must not wait (a title, a yes/no) | `deadline = 700.milliseconds`, giving `NoVerdict` if missed |

A `pin` naming an id that is not registered (for example "only my server" when no server is
configured) ends as `NoneAvailable`: nothing else is called, and `attempts` lists every other
provider as `NotPinned` with no entry for the missing one. Check that the provider exists before
offering the choice.

They combine. `mode = PreferRemote, rank = Quality, strategy = Hedged()` reads: remote providers
first, best of them first, and if the best says nothing for two seconds start the next one too.

## Offline is automatic

When the network monitor reports no network, every remote provider is skipped with the reason
`Offline` and the on-device providers are tried. No mode has to be set and nothing times out. A
user who wants to stay on the device while online gets the same effect from `OnlyOnDevice`.

## Ranking

Quality is the app's own judgement, given when the provider is registered:

```kotlin
provider(OpenAiCompatible("openrouter", …), quality = 0.9)
provider(OpenAiCompatible("home", …), quality = 0.6)
provider(GeminiNano(), quality = 0.3)
```

- `Quality`: highest first. Ties keep the configured order.
- `Speed`: shortest learned time to first answer text first. A provider with no history is
  assumed to take 1 s on device and 2 s remote, so a new provider is neither favoured nor starved.
- `Weighted(quality, speed)`: `quality × declared quality + speed × 1 / (1 + seconds)`.

A `Prefer…` mode keeps its tier in front and the ranking orders providers inside each group.
Ranking only orders providers that passed privacy, capability, mode and health; it never brings
one back.

## Strategies

Every strategy except `Sequential` sends the request to more than one provider at once. That is a
replay by definition, so they apply only to a request marked `safeToRetry` (or carrying an
idempotency key) and to a plan with at least two providers. Otherwise the request runs as
`Sequential`. A pinned request therefore always runs alone.

### Sequential (default)

One provider at a time. The next is tried only when one fails, and never after the first token
has streamed.

### Race

`Race(maxParallel = 2)`: the first providers in the plan start together. The first to produce
answer text wins; the others are cancelled and recorded as `Superseded`. If a racer fails, the
next provider in the plan takes its place at once. Costs one request per racer.

### Hedged

`Hedged(after = 2.seconds, maxParallel = 2)`: the first provider starts alone. If it has produced
no answer text after `after`, the next starts too, and the first with text wins. A failure starts
the next at once without waiting. When the first provider is healthy this costs nothing extra.
"Thinking" does not count as answer text: a provider that only reasons is still hedged.

### FastThenBest

The first on-device provider in the plan writes a draft while the highest-quality remote provider
works. The app shows the draft as it streams. When the remote answer is **complete**, the app
gets `Event.Upgrading(from, to)` and then the whole better answer; the result has
`discardPartial = true`.

- If the remote provider fails, the draft stands as the answer. Its half-finished text is never
  shown.
- If the remote provider produces text before the draft has shown any, the draft is cancelled
  and the remote answer streams normally.
- Approval for the remote provider is asked before the draft starts. If it is refused, the
  request stays on the device and nothing is replaced.
- With no on-device provider, or none allowed remotely, it behaves as `Sequential`.

### BestOf

`BestOf(count = 2, judge = null)`: `count` providers answer in full. Then one answer is kept: the
one the `Judge` picks, or without a judge the one from the highest-quality provider. Nothing is
streamed until the choice is made, so the app never shows an answer that is then swapped. It is
as slow as the slowest provider, by design. A judge that throws, hangs or returns a bad index
falls back to the quality choice.

## What does not change

- **Privacy (A-1).** No strategy starts a provider before its approval, sends it a sensitivity it
  does not accept, or sends an `Identifier` item anywhere. The privacy property test draws a
  random strategy and ranking for every case.
- **Timeouts (A-2).** Each attempt keeps its own first-token, stall and thinking limits, and a
  hard deadline ends any strategy on time.
- **The user's choice (A-3).** A pin is never raced, hedged or upgraded away from.

## Not built

- Choosing by cost. `Capabilities` carries no price data (D-015).
- Streaming the remote answer over the draft while it is still arriving. The replacement waits
  for the complete answer so that a remote failure cannot leave half an answer on screen.
- Blending several answers into one. `BestOf` keeps one answer; a judge may be an on-device model
  call, but the library does not merge text.
