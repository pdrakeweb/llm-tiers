# Decisions

Append-only. Source key: **Pete (words)** quoted, **Pete (picked)** chose an offered option,
**Proposed** awaiting confirmation.

| ID | Decision | Source |
|---|---|---|
| D-001 | A standalone library usable by any Android app, Apache 2.0; apps may depend on it or copy the pattern. | Pete (words): "we should write these as libraries that can be leveraged by ANY android app (with a permissive license) and then the skill should recommend them as dependencies or possible implementation pattern if the author doesn't want to take on the dependency" |
| D-002 | Survey community approaches first and use the best of them. Done 11 Nov 2026 (DESIGN §1, §1a). | Pete (words): "Look for patterns from the community before implementing and use the best approaches." |
| D-003 | SeaWhisper is one input, not a template. | Pete (words): "only as one input to the plan, not as a copy-paste implementation" |
| D-004 | SeaWhisper moves onto the library once the Android module ships. | Pete (words): "file a future task to change SeaWhisper to take on our new tiered-ai library" |
| D-005 | Tiers: on-device (Gemini Nano, then downloadable models), LAN (any OpenAI-compatible server, e.g. LiteLLM or llama.cpp), cloud (OpenRouter or any OpenAI-compatible URL); reachability-checked fallback. | Pete (words), via NextRep D-015/D-016: "make sure this would work if I'm running litellm and llama.cpp somewhere on my local network where if reachable it can use that and if not it can fall back to something else" |
| D-006 | Privacy is a routing rule: identifiers never leave the device; personal data only with per-request approval. | Pete (words), via NextRep D-017 |
| D-007 | Working name `tiered-ai`; final name and Maven namespace before 0.1.0. | Proposed |
