# Policy vectors

`src/main/resources/llm-tiers/policy-vectors.json` documents the routing policy by example. Each
vector is one call to `plan(request, providers, health, network)` and the plan it must produce.
The library's own tests run every vector (`PolicyVectorsTest`), and an app that copies the routing
pattern instead of depending on the library can run the same file against its implementation.

Rule order: privacy, capability, mode and pinning, health, budget.

| Field | Meaning | Default |
|---|---|---|
| `providers` | The app's configured order: a list, or the name of a set in `providerSets` | |
| provider `tier` | `ON_DEVICE`, `LAN` or `CLOUD` | |
| provider `accepts` | Sensitivities the provider may receive | `["Public","Personal"]` |
| provider `vision`, `jsonSchema`, `maxContext` | Capabilities | `false`, `false`, `4096` |
| `request.mode` | `AppOrder`, `PreferOnDevice`, `PreferLan`, `PreferCloud`, `OnlyOnDevice`, `OnlyCloud` | `AppOrder` |
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
