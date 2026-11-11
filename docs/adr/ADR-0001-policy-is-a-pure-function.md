# ADR-0001 — Routing policy is a pure function, failover lives in the executor

**Status:** proposed.

**Context.** SeaWhisper keeps a clean, testable router but puts failover (watchdogs, escalation,
cloud errors) inside a 1,900-line chat ViewModel, where it is hard to test and impossible to
reuse. local-first-llm and HybridInfer both separate a pure decision from execution.

**Decision.** `plan()` is pure and synchronous: request + providers + health + network → ordered
steps with reasons. The executor owns timeouts, fallback, first-token commit, escalation and
health updates. Apps own only presentation.

**Consequences.** Routing behaviour is documented by test vectors; apps stop re-implementing
failover; the executor becomes the place where every failure type must be handled (exhaustive
`ProviderFailure`).
