# CLAUDE.md — llm-tiers

A standalone Kotlin library that routes AI requests across on-device, LAN and cloud providers
with privacy rules and real failover. Apache 2.0. First consumer: NextRep (`pdrakeweb/nextrep`);
SeaWhisper (`pdrakeweb/sea-whisper-app`) migrates later. Nothing in this repo may know about any
consumer app.

Read `docs/DESIGN.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` before changing anything.

## Invariants (paste verbatim into every review and fix prompt)

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

## Working rules

- Tests first; routing changes come with new policy vectors.
- `:core` stays free of Android and network types (build guard).
- Every guard is shown failing on a planted violation before it counts.
- Product-level choices go to Pete as questions; record answers in `docs/DECISIONS.md`.

Pete is a professional software engineer, newer to Android as a builder: skip general engineering
explanations, explain Android-specific terms on first use, no emojis.
