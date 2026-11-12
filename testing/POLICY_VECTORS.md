# Policy vectors

`src/main/resources/llm-tiers/policy-vectors.json` documents the routing policy by example. Each
vector is one call to `plan(request, providers, health, network)` and the plan it must produce.
The library's own tests run every vector (`PolicyVectorsTest`), and an app that copies the routing
pattern instead of depending on the library can run the same file against its implementation.

Rule order: privacy, capability, mode and pinning, health, then ordering (ranking, the mode's
preferred tier, budget). Format 2 of the file has two tiers; format 1 had three.

Ranking scores: `Quality` is the declared quality. `Speed` is the learned seconds to first answer
text, or 1 s for an on-device provider and 2 s for a remote one with no history. `Weighted` is
`quality × declared quality + speed × 1 / (1 + seconds)`. Highest score first; ties keep app order.

| Field | Meaning | Default |
|---|---|---|
| `providers` | The app's configured order: a list, or the name of a set in `providerSets` | |
| provider `tier` | `ON_DEVICE` or `REMOTE` | |
| provider `quality` | The app's judgement of answer quality, 0 to 1 | `0.5` |
| provider `accepts` | Sensitivities the provider may receive | `["Public","Personal"]` |
| provider `vision`, `jsonSchema`, `maxContext` | Capabilities | `false`, `false`, `4096` |
| `request.mode` | `AppOrder`, `PreferOnDevice`, `PreferRemote`, `OnlyOnDevice`, `OnlyRemote` | `AppOrder` |
| `request.rank` | `"Quality"`, `"Speed"`, or `{"quality": w, "speed": w}` for a weighted blend | app order |
| `request.pin` | Provider id the user pinned | none |
| `request.data` | Sensitivities of the request's data items | none |
| `request.vision`, `request.jsonSchema`, `request.minContext` | What the request needs | none |
| `request.budgetFirstTokenMs` | Latency budget | none |
| `health.<id>.state` | `Ready`, `Caution`, `Out`, `Probing` | `Ready` |
| `health.<id>.typicalFirstTokenMs` | Learned first-token latency | unknown |
| `network` | `Wifi`, `Cellular`, `Ethernet`, `Other`, `None` (offline) | `Wifi` |
| `expect.steps` | Ordered plan; `needsApproval` marks providers the app must approve first | `needsApproval: false` |
| `expect.skipped` | Provider id to skip-reason code | none |

Skip-reason codes: `SensitivityNotAccepted:<sensitivity>`, `MissingCapability:<vision|jsonSchema|context|text>`,
`ExcludedByMode:<mode>`, `NotPinned`, `CoolingDown`, `Offline`.

Every provider must appear exactly once, as a step or as skipped.
