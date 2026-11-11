# Handoff — implement llm-tiers

For the Claude Code session that builds this library. You have no memory of the planning
conversation; this repo's docs are the spec.

1. Read `CLAUDE.md`, `docs/DESIGN.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`,
   `docs/DECISIONS.md`.
2. SeaWhisper (`pdrakeweb/sea-whisper-app`) is reference material only — read its `core/routing`,
   `data/llm` and ADR-0003/0004 if useful; do not copy its code.
3. Use the Android app development skill's dependency process (`platform-currency.md` §10); prove
   the empty multi-module build before library code.
4. Work through the roadmap in order (T1 → T3; T4 waits for NextRep). One branch and one pull
   request per milestone; don't merge.
5. Every milestone ends with its "proven by" column demonstrated. Contract tests against Pete's LAN
   server and OpenRouter are opt-in and read endpoints/keys from the environment; never commit a key.
6. The three invariants in `CLAUDE.md` are pasted verbatim into any review or fix prompt.
7. Stop and ask Pete before choosing the Maven namespace.
8. At the end of each milestone, summarise: what was built, what was proven and how, what is
   blocked, and the PR link.
