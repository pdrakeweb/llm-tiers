# llm-tiers — design

A **standalone, permissively licensed (Apache 2.0) library that any Android app can use**
(D-001). It sends each AI request to the best available place — on the device, a server on
the person's home network, or a cloud provider — falls back when one isn't available, and never
sends data a request isn't allowed to send. NextRep is the first consumer. The Android app skill
will recommend it, either as a dependency or as a pattern to copy for apps that would rather not
take one. Name chosen by Pete (D-007); Maven namespace decided before 0.1.0.

Pete's requirement (verbatim): *"we should write these as libraries that can be leveraged by ANY
android app (with a permissive license) and then the skill should recommend them as dependencies
or possible implementation pattern if the author doesn't want to take on the dependency … Look
for patterns from the community before implementing and use the best approaches."*

SeaWhisper (Pete's sailing assistant app) has an early implementation of the same idea. It was
reviewed on 11 Nov 2026 as one input among the community projects below, not as code to copy
(§1a). SeaWhisper moves onto the library once it ships (§10).

## 1. What already exists (surveyed 11 Nov 2026)

| Project | What it does well | Why it isn't the answer on its own | Borrow |
|---|---|---|---|
| **Firebase AI Logic Hybrid API** (Google; on-device part in beta) | Official on-device ↔ cloud routing with four clear modes: prefer on-device, prefer cloud, only on-device, only cloud | Gemini only, needs a Firebase project, no LAN or third-party providers | The four mode names; wrap it as one optional provider |
| **Koog** (JetBrains, Kotlin Multiplatform, Apache 2.0) | Mature multi-provider clients (OpenAI, Anthropic, Google, OpenRouter, Ollama, Bedrock…), retries, routing executors | Fallback triggers on a *missing* provider, not a failing one; no on-device Android models; agent framework is heavier than needed | Optional adapter so Koog users can plug their clients in |
| **local-first-llm-android** (MIT, v0.1) | Routing as a pure `decide()` function: privacy gate first, then task type, then capability, then budget; every decision carries a human-readable reason; sensitive requests are blocked rather than sent to the cloud | Early; no streaming; capability check is basic | The decision order, the "blocked, never leaked" rule, reasons on every result |
| **HybridInfer** (Apache 2.0, v0.3) | Separate timeouts for first token and for stalls between tokens, learned per model; a per-provider safety state machine that takes a failing model out of rotation and probes it back; no switching provider after the first token has streamed; replay only when the request is marked safe to retry | Very early; you supply the on-device engine | Adaptive timeouts, circuit states, first-token commit, idempotency rule |
| **LiteLLM Router** (server side) | Cooldowns after N failures per minute, retry policy per error type, special fallbacks when the prompt is too long for a model | Python, server-side | The failure semantics (cooldown, per-error retries, context-length fallback) |
| **OpenHelm** (Pete's app) | Only talks to numeric private, link-local and loopback addresses for anything it reaches on its own; binds sockets to Wi-Fi | App-specific | The private-address check for LAN providers |
| **llmdil** (Pete's desktop stack) | LAN gateway (LiteLLM + llama.cpp) with OpenRouter failover | Desktop server, not a client | The library's LAN tier should point at a gateway like this when present |

Sources:
[Android hybrid inference](https://developer.android.com/ai/hybrid) ·
[Hybrid inference blog, Jul 2026](https://android-developers.googleblog.com/2026/07/build-intelligent-android-apps-cloud-hybrid-inference.html) ·
[Koog prompt executors](https://docs.koog.ai/prompts/prompt-executors/) · [Koog](https://github.com/JetBrains/koog) ·
[local-first-llm-android](https://github.com/NagaYu/local-first-llm-android) ·
[HybridInfer](https://github.com/SimranKoul2026/HybridInfer-AAR-library) ·
[LiteLLM routing](https://docs.litellm.ai/docs/routing) ·
[ML Kit GenAI](https://developers.google.com/ml-kit/genai)

## 1a. What SeaWhisper teaches (reviewed 11 Nov 2026)

SeaWhisper routes between Gemma 3n on device (MediaPipe) and OpenRouter in the cloud. Its
routing lives in `:core:routing` (`RouterImpl`, ADR-0004) and its failover in the chat
ViewModel. What held up in real use, and what the library should do differently:

| SeaWhisper does | Keep / change |
|---|---|
| A **pre-routing rule that beats everything**: emergency queries return a verbatim snippet and never reach any model, in every mode, online or offline (ADR-0003), with tests pinning it at each layer | **Keep, generalized** as an app-supplied *short-circuit* hook that runs before routing and can answer without a model. A health app's red-flag screen is the same shape |
| Pure, deterministic `Router` with precedence: emergency → offline (real or user-forced) → confidence/task heuristics → default; user mode (Auto / On-device / Cloud) applied as an override afterwards | **Keep** the pure policy and the user override. **Add** privacy as a first-class step — SeaWhisper has none, because its data isn't sensitive |
| Watchdog on the local model: 5 s with no first token, or a 4 s stall mid-answer → cancel and fall back to cloud; skipped when the user pinned on-device | **Keep** both timeouts and the "pinned means no fallback" rule; make the timeouts learned per provider (HybridInfer) rather than fixed |
| The on-device model can **escalate itself** by emitting a sentinel (`[[ESCALATE_TO_CLOUD]]`) when its context can't answer; the app restarts the request in the cloud and replaces the partial answer so the user never sees two answers that disagree | **Keep** as an optional `Escalate` result any provider can return. The "replace, don't append" rule becomes part of the streaming contract: a fallback after partial output is reported so the UI can clear it |
| Typed, exhaustive cloud failures (`KeyMissing`, `AuthRejected`, `RateLimited`, `ServerError`, `NoNetwork`, `Timeout`, `Other`) so the UI shows an actionable message | **Keep**, extended to every provider and to "local network permission denied" |
| A "degraded" connection (captive portal, weak cellular) is still tried rather than skipped; a fast failure beats pre-emptive avoidance | **Keep** for cloud. For LAN, keep the fast probe (a LAN miss is the common case away from home) |
| Model lifecycle as a state machine (`Missing → Downloading → Ready / Failed(recoverable)`), downloads through the system `DownloadManager` so they survive navigation and process death, size check on every cold start | **Keep** in `:model-manager`; add SHA-256 verification |
| A separate **fast local client** with a 700 ms budget for tiny jobs (titles, follow-up detection), returning "no verdict" on timeout so callers use a safe heuristic | **Keep** as a request option: a hard deadline with a `NoVerdict` result instead of falling back to a slow tier |
| Routing, failover and UI state are tangled in a 1,900-line ViewModel | **Change**: failover belongs in the library's executor, so apps keep only presentation |
| Only one provider per tier; no LAN tier; no provider chain | **Change**: ordered chains per tier, LAN tier with private-address rules |

**Conclusion:** no existing library covers on-device + LAN + any cloud with privacy rules and
real failure handling. Each solves one slice well. `llm-tiers` is a small routing core built from
the best of each, with thin adapters so apps can reuse providers they already have (Firebase,
Koog).

## 2. Goals and non-goals

**Goals**
- One call site: `ai.generate(request)` returns a result and says where it ran and why.
- Tiers: on-device (Gemini Nano via ML Kit; downloaded open models via LiteRT-LM), LAN
  (any OpenAI-compatible server: LiteLLM, llama.cpp, Ollama), cloud (OpenRouter, any
  OpenAI-compatible URL, optional Firebase/Gemini, optional Koog clients).
- A request can say what it needs (text, images, structured JSON, a context length) and what it
  is allowed to send; routing respects both before anything else.
- Fast, honest failover: away from home the LAN tier fails over in about a second and stays
  failed over until the network changes.
- Works offline with on-device providers alone, and degrades to "no AI available" with a reason,
  never a hang.
- Kotlin, coroutines, streaming; core module free of Android types so it can go multiplatform.

**Non-goals** — an agent framework; prompt templating; a model server; vector search; billing.

## 3. Concepts

| Concept | Meaning |
|---|---|
| **Provider** | One place a request can run: id, tier (on-device / LAN / cloud), capabilities (text, vision, JSON, max context), which data sensitivity it may receive |
| **Request** | Messages (+ images), what it needs, a task hint, data items each tagged with a sensitivity, latency budget, and whether it is safe to retry |
| **Sensitivity** | `Public` (send anywhere allowed), `Personal` (send only after the app's approval callback says yes for this request), `Identifier` (never leaves the device; stripped). An app maps its own data classes onto these |
| **Short-circuit** | An app hook that runs before any routing and may answer without a model (SeaWhisper's emergency verbatim rule; a health app's red-flag screen) |
| **Policy** | A pure function that turns a request plus provider health into an ordered plan, with a reason for each step and for each provider skipped |
| **Health** | Per provider and per network: last probe result, a cooldown after repeated failures, and a state (`Ready → Caution → Out → Probing → Ready`) |
| **Result** | Output, `answeredBy`, `tier`, `fellBack`, the attempts made and why each failed |

## 4. How a request is routed

0. **Short-circuit.** If the app's hook answers, return that answer. No model is called, in any
   mode, online or offline.
1. **Privacy first.** Remove `Identifier` items. If the request carries `Personal` items, cloud and
   LAN providers are allowed only after the app's `approve(request, provider)` callback returns
   yes (an app typically shows a preview of exactly what will be sent). If no allowed provider remains, return
   `Blocked(reason)` — never send it anyway.
2. **Capability.** Drop providers that can't handle the request (no vision, context too short, no
   structured output).
3. **Mode.** Apply the request's mode — `PreferOnDevice`, `PreferLan`, `PreferCloud`,
   `OnlyOnDevice`, `OnlyCloud` — or the app's ordered list.
4. **Health.** Skip providers that are cooling down or failed their probe on the current network.
5. **Budget.** If a latency or cost budget is set and the first choice can't meet it, move a
   provider that can to the front.
6. **Execute in order.** Each attempt has a first-token timeout and a stall timeout (learned per
   provider from its own history; fixed defaults until warmed up). Fall back on: unreachable,
   timeout before the first token, rate limit, server error, prompt too long (go to a provider with
   a longer context), on-device model missing or busy. Never switch providers after the first token
   has streamed — except when a provider returns `Escalate` (it decided it can't answer), in
   which case the result reports `discardPartial = true` so the app replaces the partial answer.
   A provider the user pinned is never failed over silently. Replay only requests marked safe to
   retry. Requests with a hard deadline return `NoVerdict` instead of falling back to a slower tier.
7. **Report.** Every result carries where it ran, the path taken, and why.

## 5. Networks and the LAN tier

- **Probe:** `GET /v1/models` with about a one-second timeout; cached per network (Android
  `Network` handle), cleared when the network changes, so coming home switches back without a
  restart.
- **Private addresses only for plain HTTP.** HTTP is allowed only when the host resolves to a
  loopback, link-local, private (RFC 1918 / CGNAT) or `.local` address (the OpenHelm rule);
  anything else must be HTTPS. The library ships a network-security-config snippet the app opts
  into.
- **Local network permission.** At targetSdk 37+ the library exposes `needsLocalNetworkPermission()`
  and a result reason `LocalNetworkPermissionDenied`, so the app asks from its settings screen and
  the router falls through cleanly until then.
- **Bind to Wi-Fi** optionally, so a LAN request never leaks onto cellular.

## 6. On-device tier

- **Gemini Nano** through the ML Kit GenAI Prompt API: feature-status check, download state,
  foreground-only rule, quota "busy" errors mapped to a short cooldown.
- **Downloaded models** through LiteRT-LM: an optional `model-manager` module (size shown before
  download, unmetered by default, resumable, SHA-256 verified, deletable).
- **Firebase hybrid** as one optional provider for apps already on Firebase.

## 7. Modules

```
:core            pure Kotlin — request/result types, policy, health, executor, fake clock
:android         network monitor, local-network permission helper, Nano provider, LiteRT-LM provider
:openai          OpenAI-compatible client (chat completions, streaming, JSON schema, images)
:model-manager   optional download/verify/delete for on-device models
:firebase        optional adapter for Firebase AI Logic (Gemini cloud + hybrid)
:koog            optional adapter exposing Koog clients as providers
:testing         fake providers with scripted latency/failure, deterministic network switcher
```

## 8. API sketch

```kotlin
val ai = TieredAi {
    provider(OpenAiCompatible(id = "home-llama", baseUrl = "http://192.168.1.20:4000/v1", tier = LAN))
    provider(OpenAiCompatible(id = "openrouter", baseUrl = "https://openrouter.ai/api/v1", apiKey = keyStore["openrouter"], tier = CLOUD))
    provider(GeminiNano(context))
    provider(LiteRtModel(context, model = downloaded("gemma-text")))
    order("home-llama", "openrouter", "gemini-nano", "gemma-text")
    approve { request, provider -> previewSheet.ask(request.personalItems, provider) }
}

val result = ai.generate(Request(
    messages = listOf(user("Build a 30-minute session…")),
    needs = Needs(structuredJson = routineSchema),
    data = listOf(Item(limits, Public), Item(conditions, Personal)),
    mode = PreferLan,
))
when (result) {
    is Answer   -> show(result.output, by = result.answeredBy)
    is Blocked  -> explain(result.reason)
    is NoneAvailable -> offerRulesEngine(result.attempts)
}
```

## 9. Testing

- Policy is a pure function: table-driven tests and shared test vectors (the local-first-llm
  approach) so the routing rules are documented by example.
- Executor tests with fake providers and a fake clock: every fallback trigger, first-token commit,
  retry-safety, cooldown and recovery.
- Privacy property test: for random requests and provider sets, no `Identifier` item and no
  unapproved `Personal` item ever reaches a non-on-device provider.
- Network-switch tests: LAN reachable → not → reachable, verifying failover time and switch-back.
- Contract tests against a real LiteLLM/llama.cpp endpoint (opt-in, Pete's LAN), OpenRouter
  (opt-in, needs a key), and an emulator image with Nano where available.

## 10. Plan and follow-ups

| Step | Outcome |
|---|---|
| T0 | ~~Compare with SeaWhisper~~ — done (§1a) |
| T1 | `:core` + `:openai` + `:testing`; policy vectors; executor fault tests |
| T2 | `:android` (network monitor, local-network helper, Nano provider) |
| T3 | `:model-manager` + LiteRT-LM provider |
| T4 | First consumer integration (NextRep); optional `:firebase` and `:koog` adapters |
| T5 | First release (Apache 2.0, Maven Central); the Android skill recommends it (dependency or pattern) |
| Follow-up | **Move SeaWhisper onto `llm-tiers`** once T2 ships (D-004) |

Open questions for Pete: library name; whether to publish under a personal namespace or an
organisation; whether the `:openai` client should wrap an existing Kotlin OpenAI client or stay
in-house (the surface is small, and fewer dependencies helps apps that copy the pattern).
