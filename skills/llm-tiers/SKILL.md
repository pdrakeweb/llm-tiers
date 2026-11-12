---
name: llm-tiers
description: Integrates the llm-tiers library into an Android app (Kotlin) so AI requests run on the device or on any remote OpenAI-compatible endpoint, with fallback, privacy approval and typed results. Use when adding AI text generation to an Android app that should work offline, use a user's own server or cloud key, download on-device models, or fall back between providers; when wiring Gemini Nano, LiteRT-LM models, OpenRouter, LiteLLM, llama.cpp or Ollama behind one call; or when reviewing an app that already uses llm-tiers. Do NOT use for iOS, for server-side code, for building an agent framework, or for changing the llm-tiers library itself.
---

# Using llm-tiers in an Android app

`llm-tiers` gives an app one call, `ai.stream(request)` or `ai.generate(request)`, that runs on
the best available provider: on the device (Gemini Nano, a downloaded model) or remote (any
OpenAI-compatible URL). It falls back when a provider fails, asks before personal data leaves the
device, never hangs, and always says where a request ran and why.

This skill is used from a checkout of the library, because the library is not on Maven Central
yet. If you only have this folder, clone `https://github.com/pdrakeweb/llm-tiers`. Paths below
are relative to that checkout.

## Do these, in this order

1. **Publish the library locally.** In the checkout: `./gradlew publishToMavenLocal`. This puts
   `io.github.pdrakeweb:llm-tiers-{core,openai,android,model-manager,testing}:0.1.0-SNAPSHOT` in
   the local Maven cache. It needs JDK 17+ and the Android SDK (platform 37, build tools 36).
2. **Read `docs/INTEGRATION.md` and follow its nine steps.** It is ordered, and every code block
   comes from a working app.
3. **Copy from `examples/compose-hilt/`, not from `sample/`.** The example is a real-shaped app
   (Compose, Hilt, ViewModels, an encrypted key store, unit tests). `sample/` is a test harness
   driven by scripts and is the wrong shape to copy.
4. **Choose routing with `docs/STRATEGIES.md`** only if the default (configured order, one at a
   time, fall back on failure) is not what the app needs.
5. **Verify**, in this order: the app builds; unit tests with `FakeProvider` pass; the app is
   launched on a device or emulator and a request is sent. Then walk the checklist at the end of
   `docs/INTEGRATION.md`.

`docs/ANDROID.md` has the platform details (plain HTTP to home servers, the Android 17
local-network permission, Gemini Nano, downloaded models) when a step needs more than the guide
gives.

## The three rules

Paste these verbatim into any review or fix prompt for code that uses the library.

> **A-1 · NOTHING LEAVES THE DEVICE WITHOUT PERMISSION.** Identifier items never reach a LAN or
> cloud provider. Personal items reach one only after the app's approval for that request and that
> provider. Request and response content is never logged.
>
> **A-2 · NEVER HANG, NEVER GUESS.** Every request ends in a typed result — Answer, Answered,
> Blocked, NoVerdict or NoneAvailable — within its timeouts, and every result says where it ran and
> why.
>
> **A-3 · RESPECT THE USER'S CHOICE.** A provider the user pinned is never silently replaced, and a
> short-circuit answer from the app is never sent to a model.

The library enforces them. These are the ways an integration defeats them anyway:

| Mistake | Why it is wrong | Do instead |
|---|---|---|
| Personal data pasted into the prompt string | The library cannot see inside a string, so no approval is asked | Put it in `Request.data` as `Item(value, Sensitivity.Personal)` |
| Logging prompts, answers or item values | Breaks A-1 in your own code | Use the library's `Logger`: ids, timings, failure types |
| An `OkHttpClient` with a body-logging interceptor | Logs every prompt and answer | Pass a client without one |
| `when (result) { is Answer -> … else -> … }` | A new or forgotten result is silently swallowed | Exhaustive `when`, no `else` |
| Your own `withTimeout` or retry loop around the call | Double-sends requests and fights the library's fallback | Set `deadline` on the request, or nothing |
| A new `TieredAi` per screen or per request | Throws away learned health and timings; every request pays the slow path | One instance for the process |
| Appending tokens and ignoring `Completed` | Shows discarded drafts and misses `incomplete` | On `Completed`, show `result.text`; clear on `discardPartial` and `Upgrading` |
| "Only this provider" done by reordering | It can still fall back to another | `pin = ProviderId("…")` |
| Leaving `safeToRetry` false for plain text generation | No fallback after a timeout, no concurrent strategies | `safeToRetry = true` unless running twice would do harm |
| Asking for the local-network permission at startup | Users refuse prompts with no context | Ask on the screen where they enter a home server |

## The API in one screen

```kotlin
// build once
val ai = TieredAi {
    provider(OpenAiCompatible("remote", "https://openrouter.ai/api/v1", model = "…", apiKey = key, client = http), quality = 0.9)
    provider(GeminiNano(), quality = 0.3)
    network = AndroidNetworkMonitor(context)
    approve { request, provider -> showPreviewAndAsk(request.personalItems, provider) }
}

// ask
val request = Request(
    messages = listOf(system("Be brief."), user("…")),
    data = listOf(Item("…", Sensitivity.Personal)),
    safeToRetry = true,
    // optional: mode = Mode.PreferOnDevice, pin = ProviderId("remote"),
    //           rank = Ranking.Quality, strategy = Strategy.FastThenBest, deadline = 700.milliseconds
)

// stream: Started, Thinking, Token, FellBack, Upgrading, then exactly one Completed
ai.stream(request).collect { event -> … }

// every request ends in one of five results
when (val result = ai.generate(request)) {
    is Result.Answer -> result.text            // + answeredBy, incomplete, discardPartial, attempts
    is Result.Answered -> result.shortCircuit  // the app's own hook answered; no model ran
    is Result.Blocked -> result.reason         // privacy left nowhere to send it; nothing was sent
    is Result.NoVerdict -> result.attempts     // the hard deadline passed
    is Result.NoneAvailable -> result.attempts // tried or skipped, each with a reason
}
```

Packages: `io.github.pdrakeweb.llmtiers` (core), `.openai` (`OpenAiCompatible`), `.android`
(`AndroidNetworkMonitor`, `GeminiNano`, `LiteRtModel`, `privateCleartextOnly`,
`requiringLocalNetworkPermission`, `needsLocalNetworkPermission`, `ACCESS_LOCAL_NETWORK`),
`.modelmanager` (`ModelManager`, `ModelSpec`, `ModelState`, `ModelFit`, `OpenModels`), `.testing`
(`FakeProvider`, `SchedulerClock`, `ScriptedNetworkMonitor`).

## If something is not covered

Read the public source rather than guessing: every public type has a comment, and the files are
small (`core/src/main/kotlin/io/github/pdrakeweb/llmtiers/`). If the guide left you guessing, say
so in your report: the guide is meant to leave nothing to guess, and a gap in it is a bug.

## What the library does not do

An agent framework or tool calling; prompt templates; conversation storage (the app keeps its own
history and passes `messages`); choosing by cost; iOS. It is not yet published to Maven Central.
