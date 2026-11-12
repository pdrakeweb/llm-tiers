# Validating the usage skill with agents that know nothing

The test: give an agent with no knowledge of this library only `skills/llm-tiers/SKILL.md` and
what it points to, ask it to build a different app from the example, forbid it from reading the
library source unless the docs leave no other way, and have it report every place it had to
guess. Fix what it reports, then repeat with a new agent and a new app.

Run on 11 Nov 2026, on an Android 17 (API 37) emulator.

| | Round 1: "Notes" | Round 2: "Cookbook" |
|---|---|---|
| App | Notes with "Summarise" (private note body) and an instant "Suggest title" (800 ms) | Streaming Q&A with private dietary notes, an instant tag (600 ms), and "Only my endpoint" |
| Compiled | First build | First build |
| Unit tests with the fakes | 8 of 8 | 12 of 12 |
| Launched on the emulator | Yes | Yes |
| Real answer seen on the device | No (no endpoint was provided) | Yes: answer, "Answered by remote", approval dialog, refusal, cooldown message, recovery after a settings change |
| Library source read | Never | Once: the `FakeProvider` constructor signature |
| Gaps reported | 8 | 7 |

## What round 1 found, all fixed before round 2

1. Whether a deadline covers the approval wait, and what to do for instant jobs with personal
   data. This would have shipped a dialog nobody could answer. Now a rule in the guide, the skill
   and the checklist; the example's `approve` refuses deadline requests.
2. `SkipReason` values were not listed, so a request made just after a failure fell through to a
   useless message. Now every failure and skip reason is listed with the cooldown rules.
3. How `Request.data` reaches the model. Now stated with a rendered example.
4. `generate` on the example's interface. Now a default method.
5. No worked example of a deadline. Now a test in the example and in the guide.
6. Types implied rather than stated. Now a reference table.
7. Whether to declare `ACCESS_NETWORK_STATE`. Now stated (it is merged from the library).
8. The 60 MB cost of `llm-tiers-android`. Now stated, with the remote-only alternative.

## What round 2 found, all fixed afterwards

1. The `FakeProvider` constructor and its `Script` type were not printed. Now in step 9.
2. `GeminiNano()`'s id (`gemini-nano`) was never stated, and the guide's test snippets used
   `nano`. Now stated and the snippets corrected.
3. Pinning an id that is not registered. Now in `STRATEGIES.md`.
4. An instant job can put an on-device provider into cooldown, which changes the next request's
   result from `NoneAvailable` to `Blocked`. Now explained beside the refusal rule.
5. The order of `attempts`. Now stated.
6. Several fields missing from the reference table. Added.
7. The guide's request snippet sent a personal item unconditionally. Now guarded, with the reason.

## What this does and does not show

- Two agents, on two different apps, built working integrations in one pass from the skill and
  docs. Neither had to guess the overall shape; both copied it from the guide and example.
- Round 2's gaps were all one-sentence fixes. The fixes made after round 2 have **not** been
  re-tested by a third agent.
- Neither agent could exercise Gemini Nano, a downloaded model, a real HTTPS endpoint with a key,
  or the local-network permission prompt. Those paths in the guide are untested by this method.
- Both agents ran on the machine that built the library, with its toolchain already working. The
  skill's first step (publish locally) has not been tried on a clean machine.
