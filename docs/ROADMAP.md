# llm-tiers — roadmap

| Milestone | Delivers | Proven by |
|---|---|---|
| **T1 · Core** | `:core`, `:openai`, `:testing`; policy vectors; executor | Every fallback, escalation, pinning and privacy test passes; `:openai` streams from a real OpenAI-compatible endpoint |
| **T2 · Android** | `:android` network monitor, private-address rules, local-network helper, Gemini Nano provider; `:sample` | Sample app on the Pixel 10 Pro: LAN when home, cloud when not, Nano offline; failover ≤ 1.5 s |
| **T3 · On-device models** | `:model-manager`, LiteRT-LM provider | Download survives app kill; checksum failure reported; model deletable; offline answers on a device without Nano |
| **T4 · Adapters + first consumer** | `:firebase`, `:koog`; NextRep integration | NextRep's routine generation runs through the library with its preview sheet |
| **0.1.0 release** | Maven Central, docs, "use it or copy it" guide | — |
| **Follow-up** | SeaWhisper migrates onto the library | SeaWhisper's router and watchdog replaced by library calls; its emergency rule becomes a short-circuit hook |
