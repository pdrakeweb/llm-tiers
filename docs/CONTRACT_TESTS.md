# Contract tests

`:openai` has tests that talk to real OpenAI-compatible servers. They are opt-in: each is reported
as skipped unless its environment variables are set. Keys are read from the environment only. They
are never written to the repository, never printed, and never made a Gradle task input.

| Variable | Meaning |
|---|---|
| `LLM_TIERS_LAN_BASE_URL` | API root of a LiteLLM, llama.cpp, Ollama or vLLM server, including `/v1` (for example `http://192.168.1.20:4000/v1`) |
| `LLM_TIERS_LAN_MODEL` | Model name that server accepts |
| `LLM_TIERS_LAN_API_KEY` | Optional. Bearer key, if the server needs one |
| `LLM_TIERS_OPENROUTER_API_KEY` | OpenRouter key |
| `LLM_TIERS_OPENROUTER_MODEL` | OpenRouter model id (for example a small, cheap one) |

The easiest way to set them is a `.env` file in the repository root. Copy `.env.example` to
`.env` and fill in what you have. `.env` is ignored by git, Gradle hands its values to the test
process only, and a variable set in the real environment wins over the file.

```bash
./gradlew :openai:test --tests "*ContractTest*"
```

Or set them in the shell (PowerShell):

```powershell
$env:LLM_TIERS_LAN_BASE_URL = "http://192.168.1.20:4000/v1"
$env:LLM_TIERS_LAN_MODEL = "your-model"
./gradlew :openai:test --tests "*ContractTest*"
```

Each test probes the server (`GET /models`), sends one short prompt through `TieredAi`, and
asserts that tokens streamed and the result names the provider. One line per run reports the token
count and timings; no prompt or response text is printed.
