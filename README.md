# llm-tiers

One AI call for Android apps that runs in the best available place — on the device, or on any
remote OpenAI-compatible endpoint (a server on the home network, a self-hosted gateway, a cloud
service) — falls back when one isn't available, and never sends data a request isn't allowed to
send.

**Status:** T1 (core), T2 (Android) and T3 (downloaded on-device models) implemented. Adapters and
the first consumer integration (T4) not started. Not yet on Maven Central; publish locally with `./gradlew publishToMavenLocal`.

```kotlin
val ai = TieredAi {
    provider(OpenAiCompatible("home-llama", "http://192.168.1.20:4000/v1", model = "gemma"), quality = 0.6)
    provider(OpenAiCompatible("openrouter", "https://openrouter.ai/api/v1", model = "…", apiKey = key), quality = 0.9)
    provider(GeminiNano(), quality = 0.3)
    order("home-llama", "openrouter")
    approve { request, provider -> previewSheet.ask(request.personalItems, provider) }
}

when (val result = ai.generate(Request(listOf(user("Build a 30-minute session…")), safeToRetry = true))) {
    is Result.Answer -> show(result.text, by = result.answeredBy)   // result.incomplete says if it was cut short
    is Result.Answered -> show(result.shortCircuit)                 // the app's own hook answered; no model ran
    is Result.Blocked -> explain(result.reason)                     // privacy left nowhere to send it
    is Result.NoVerdict -> useHeuristic()                           // hard deadline missed
    is Result.NoneAvailable -> explain(result.attempts)             // what was tried or skipped, and why
}
```

`ai.stream(request)` gives the same thing as a flow: `Started`, `Thinking`, `Token`, `FellBack`,
`Upgrading`, `Completed`.

A request can also say how providers are chosen and run: on-device or remote first, ranked by
quality or speed, raced, hedged, a quick on-device draft replaced by a better remote answer, or
the best of several. See [`docs/STRATEGIES.md`](docs/STRATEGIES.md).

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure Kotlin. Request and result types, the routing policy (`plan()`, a pure function), health tracking, and the executor: timeouts, fallback, escalation, pinning, retry safety. No Android or network types (build guard) |
| `:openai` | Streaming client for any OpenAI-compatible server (OpenRouter, LiteLLM, llama.cpp, Ollama, vLLM) over OkHttp |
| `:model-manager` | Download, SHA-256 verify and delete on-device models through Android's system DownloadManager |
| `:litert` | Optional: runs a downloaded open model on the device with LiteRT-LM. Separate because its native runtime is about 23 MB per ABI |
| `:android` | Network monitor, private-address rule for plain HTTP, local-network permission helper, Gemini Nano provider. See [`docs/ANDROID.md`](docs/ANDROID.md) |
| `:sample` | A one-screen app: provider settings, a prompt box, and a log of where each request ran and why |
| `:testing` | Fake providers with scripted latency and failures, fake clocks, a scripted network monitor, and the policy vectors as JSON |

## Build and test

JDK 17 or newer. The Android modules also need the Android SDK (platform 37, build tools 36).

```bash
./gradlew build
```

Contract tests against real endpoints are opt-in; see [`docs/CONTRACT_TESTS.md`](docs/CONTRACT_TESTS.md).

## Docs

| Doc | What it answers |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | Prior art, routing rules, privacy, networks |
| [`docs/INTEGRATION.md`](docs/INTEGRATION.md) | How to add the library to an app, step by step, with a working example in [`examples/compose-hilt`](examples/compose-hilt) |
| [`skills/llm-tiers/SKILL.md`](skills/llm-tiers/SKILL.md) | The skill an AI assistant uses to integrate the library |
| [`docs/STRATEGIES.md`](docs/STRATEGIES.md) | Which providers run, in what order, and how: modes, pinning, ranking, race, hedged, fast-then-best, best-of |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Modules, core types, policy, executor, networking, testing |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Milestones T1–T4 and what proves each |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | What is settled and who settled it |
| [`testing/POLICY_VECTORS.md`](testing/POLICY_VECTORS.md) | The routing rules by example, and the vector file format |
| [`docs/handoff/IMPLEMENT.md`](docs/handoff/IMPLEMENT.md) | Instructions for the implementation session |
| [`docs/handoff/SKILL_VALIDATION.md`](docs/handoff/SKILL_VALIDATION.md) | How the usage skill was tested with agents that knew nothing, and what they found |
| [`docs/handoff/ANDROID_SKILL_LESSONS.md`](docs/handoff/ANDROID_SKILL_LESSONS.md) | What this build taught that should go back into the Android app skill |

Apache 2.0.
