# llm-tiers — architecture

How the library is built. Behaviour is in `DESIGN.md`; keep the two in step.

## 1. Modules

```
:core            Pure Kotlin. Request/Result/Event types, Sensitivity, Provider and Capability
                 model, Policy (pure function), Health tracker, Executor (streaming, timeouts,
                 fallback, first-token commit), typed failures, Clock abstraction.
:openai          OpenAI-compatible client over OkHttp + kotlinx.serialization: chat completions,
                 SSE streaming, JSON-schema output, image parts, /v1/models probe. Works with
                 OpenRouter, LiteLLM, llama.cpp server, Ollama, vLLM.
:android         NetworkMonitor (keys health by Network handle), private-address and cleartext
                 rules, local-network permission helper (targetSdk 37+), Gemini Nano provider
                 (ML Kit GenAI Prompt API), LiteRT-LM provider, encrypted key store.
:model-manager   Download / verify (SHA-256) / delete on-device models through the system
                 DownloadManager; state machine Missing → Downloading → Ready | Failed(recoverable).
:firebase        Optional: Firebase AI Logic (Gemini cloud and its hybrid mode) as providers.
:koog            Optional: wrap Koog LLM clients as providers.
:testing         Fake providers with scripted latency, tokens, stalls and failures; fake clock;
                 scripted network switcher; policy test vectors.
:sample          Minimal Android app: a prompt box, the provider chain editor, and a log of where
                 each request ran and why.
```

`:core` never depends on Android or a network library (build guard).

## 2. Core types

```kotlin
interface Provider {
    val id: ProviderId
    val tier: Tier                                   // ON_DEVICE or REMOTE (D-033)
    val probeBeforeUse: Boolean                      // run probe() before each request (D-033)
    val accepts: Set<Sensitivity>                    // which data it may receive
    suspend fun capabilities(): Capabilities         // text, vision, jsonSchema, maxContext
    suspend fun probe(): Reachability                // cheap health check
    fun stream(request: PreparedRequest): Flow<ProviderEvent>
}

sealed interface ProviderEvent {
    data class Token(val text: String) : ProviderEvent
    data class Done(val usage: Usage?) : ProviderEvent
    data object Thinking : ProviderEvent             // "working, no answer text yet" (reasoning model)
    data object Escalate : ProviderEvent             // "I can't answer this; try the next tier"
    data class Failed(val failure: ProviderFailure) : ProviderEvent
}

sealed interface ProviderFailure {                  // exhaustive by design
    data object Unreachable; data object Timeout; data object FirstTokenTimeout; data object Stalled
    data object AuthRejected; data object KeyMissing; data object RateLimited
    data class ContextTooLong(val max: Int); data class Server(val code: Int)
    data object ModelNotReady; data object Busy; data object LocalNetworkPermissionDenied
    data class Other(val detail: String)
}

sealed interface Result {
    data class Answer(val text: String, val answeredBy: ProviderId, val tier: Tier,
                      val attempts: List<Attempt>, val discardPartial: Boolean,
                      val incomplete: ProviderFailure? = null) : Result   // D-009
    data class Answered(val shortCircuit: Any) : Result   // app hook answered; no model ran
    data class Blocked(val reason: String) : Result         // privacy left no allowed provider
    data class NoVerdict(val attempts: List<Attempt>) : Result // hard deadline missed
    data class NoneAvailable(val attempts: List<Attempt>) : Result
}
```

Streaming API: `ai.stream(request): Flow<Event>` emits `Started(provider)`, `Thinking(provider)`
(once, when a reasoning model starts thinking), `Token`, `FellBack(from, to, reason, discardPartial)`,
then `Completed(result)`.

## 3. Policy

> **Tiers (D-033).** Earlier text in this document and in DESIGN.md speaks of three tiers:
> on-device, LAN and cloud. There are now two, `ON_DEVICE` and `REMOTE`. A home server and a
> cloud service differ only in per-provider properties (`probeBeforeUse`, the address rules in §6).
> Modes are `AppOrder`, `PreferOnDevice`, `PreferRemote`, `OnlyOnDevice`, `OnlyRemote`.

A pure function `plan(request, providers, health, network) -> Plan` with `Plan.steps` (ordered
providers) and `Plan.skipped` (provider, reason). Order of rules: short-circuit (done by the
executor before `plan`) → privacy → capability → user mode / pinning → health → budget. Documented
by JSON test vectors in `:testing` so copies of the pattern can check themselves.

## 4. Executor

A request's `strategy` chooses how the plan is run (ADR-0002, `docs/STRATEGIES.md`):
`Sequential` (default), `Race`, `Hedged`, `FastThenBest`, `BestOf`. Its `rank` (`AppOrder`,
`Quality`, `Speed`, `Weighted`) is part of the pure policy. What follows describes one attempt
and the sequential path; the concurrent strategies run several such attempts side by side under
the same rules.

- Runs steps in order. Per attempt: first-token timeout and stall timeout, learned per provider as
  a high percentile of observed values (fixed defaults until 20 samples).
- A provider that reports `Thinking` is alive, not silent (D-031). The first-token timeout bounds
  the wait for its first sign of life. After that it must keep showing life within the stall
  timeout and produce its first token within a thinking allowance (2 minutes by default). The
  learned first-token timeout comes from the first sign of life; latency budgets use the time to
  the first answer text.
- Falls back on any `ProviderFailure` except `AuthRejected`/`KeyMissing` on a *pinned* provider
  (those surface immediately — the user chose it).
- First-token commit: after the first token, a failure ends the request with what was received and
  the failure reason — except `Escalate`, which falls through with `discardPartial = true`.
- Replays a request only when `safeToRetry` is true or an idempotency key is set.
- Health: `Ready → Caution` on a failure, `Out` after N failures in a window (cooldown), `Probing`
  after cooldown, back to `Ready` on success. Keyed by provider and network.

## 5. Privacy

- `Identifier` items are removed before planning; there is no API to send one.
- `Personal` items require `approve(request, provider)` to return true for that request and that
  provider; on-device providers never need approval.
- A property test asserts: for random requests and provider sets, no unapproved `Personal` item and
  no `Identifier` item reaches a remote provider.
- The library never logs request or response content; logs carry provider ids, timings and failure
  types only.

## 6. Networking rules (`:android`)

- Plain HTTP only to loopback, link-local, RFC 1918, CGNAT or `.local` hosts, checked after DNS
  resolution; everything else must be HTTPS. A network-security-config snippet is documented for
  apps to include.
- Optional binding to the Wi-Fi network for home-network providers so traffic never falls back to cellular.
- `needsLocalNetworkPermission()` and `LocalNetworkPermissionDenied` for targetSdk 37+.

## 7. Testing

| Layer | How |
|---|---|
| Policy | Table-driven tests + shared JSON vectors |
| Executor | Fake providers and fake clock: every failure type, first-token commit, escalation, pinning, retry-safety, cooldown and recovery |
| Privacy | Property test (§5) |
| Network switching | Scripted network monitor: LAN reachable → not → reachable; asserts failover ≤ 1.5 s and switch-back |
| Contract | `:openai` against a real LiteLLM / llama.cpp on Pete's LAN (opt-in) and OpenRouter (opt-in, key from env); Nano on a supported device |
| Sample app | Journey scenario on an emulator and the Pixel 10 Pro |

## 8. Build and release

Kotlin, Gradle version catalog, JDK 17, Android modules at minSdk 26 (Nano and LiteRT-LM providers
check availability at runtime), Apache 2.0, Maven Central under a namespace Pete chooses,
binary-compatibility validator, Dokka.
