# llm-tiers

One AI call for Android apps that runs in the best available place — on the device, on a server
on the person's home network, or in the cloud — falls back when one isn't available, and never
sends data a request isn't allowed to send.

**Status:** T1 (core), T2 (Android) and T3 (downloaded on-device models) implemented. Adapters and
the first consumer integration (T4) not started. Not yet published.

```kotlin
val ai = TieredAi {
    provider(OpenAiCompatible("home-llama", "http://192.168.1.20:4000/v1", model = "gemma", tier = Tier.LAN))
    provider(OpenAiCompatible("openrouter", "https://openrouter.ai/api/v1", model = "…", tier = Tier.CLOUD, apiKey = key))
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

`ai.stream(request)` gives the same thing as a flow: `Started`, `Token`, `FellBack`, `Completed`.

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure Kotlin. Request and result types, the routing policy (`plan()`, a pure function), health tracking, and the executor: timeouts, fallback, escalation, pinning, retry safety. No Android or network types (build guard) |
| `:openai` | Streaming client for any OpenAI-compatible server (OpenRouter, LiteLLM, llama.cpp, Ollama, vLLM) over OkHttp |
| `:model-manager` | Download, SHA-256 verify and delete on-device models through Android's system DownloadManager |
| `:android` | Network monitor, private-address rule for plain HTTP, local-network permission helper, Gemini Nano provider, LiteRT-LM provider. See [`docs/ANDROID.md`](docs/ANDROID.md) |
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
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Modules, core types, policy, executor, networking, testing |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Milestones T1–T4 and what proves each |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | What is settled and who settled it |
| [`testing/POLICY_VECTORS.md`](testing/POLICY_VECTORS.md) | The routing rules by example, and the vector file format |
| [`docs/handoff/IMPLEMENT.md`](docs/handoff/IMPLEMENT.md) | Instructions for the implementation session |
| [`docs/handoff/ANDROID_SKILL_LESSONS.md`](docs/handoff/ANDROID_SKILL_LESSONS.md) | What this build taught that should go back into the Android app skill |

Apache 2.0.
