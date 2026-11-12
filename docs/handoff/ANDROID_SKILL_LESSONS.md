# Lessons for the Android app development skill

What building `llm-tiers` (T1 to T3, 11 Nov 2026) taught that the skill does not say, says
wrongly, or says only for apps. Each entry names the skill file it would change, what happened,
and how sure the lesson is: **Observed** means it happened in this session and the evidence is in
this repository; **Inferred** means it follows from what was observed but was not tested directly.

The skill was read at the version installed on 11 Nov 2026 (pins "verified 2026-09-04").

## Corrections: the skill is wrong or out of date

### 1. `http://10.0.2.2` no longer "just works" on Android 17

- **File:** `windows-toolchain-and-emulators.md` (the line saying no `adb reverse` is needed, use
  `http://10.0.2.2:<port>`), and `platform-currency.md` §4, which lists API 36 behaviours but has
  no API 37 section.
- **Observed:** on an API 37 emulator with an app at targetSdk 37, `10.0.2.2` is a local-network
  address. Without the `ACCESS_LOCAL_NETWORK` runtime permission, connections to it get no
  reply: there is no error, just a timeout. A loopback address reached through
  `adb reverse tcp:PORT tcp:PORT` is not affected.
- **Proposed text:** at targetSdk 37+, either grant the permission in the test setup
  (`adb shell pm grant <pkg> android.permission.ACCESS_LOCAL_NETWORK`) or reach the host through
  `adb reverse` and `127.0.0.1`. Say which one a test uses and why, because they exercise
  different things. Revoke it (`pm revoke`) to test the denied path.
- **Evidence:** `sample/journey/journey.sh` steps 1 and 2; `docs/ANDROID.md`.

### 2. The system DownloadManager cannot reach local-network addresses on Android 17

- **File:** `permissions-storage-cloud.md` (wherever downloads are discussed), and the same new
  API 37 section.
- **Observed:** a `DownloadManager` request for `http://10.0.2.2:18403/…` stayed `PENDING` at zero
  bytes indefinitely. The server was never contacted and no error was reported, although the
  app had been granted the local-network permission earlier in the same session (the grant was not
  re-checked at that moment). The identical file through a loopback address
  downloaded at once.
- **Proposed text:** anything fetched with `DownloadManager` must be on a public address. A file
  served from a LAN host will hang without a failure. This is the worst kind of failure by the
  skill's own house rule (a silent no-op), so call it out.
- **Evidence:** `sample/journey/journey-models.sh` header; PR #3.

### 3. Version pins were two releases behind, and there is no §10

- **File:** `platform-currency.md` §1 and §2.
- **Observed:** the skill pins AGP 9.4.0 and Gradle 9.6.0. Current stable on 11 Nov 2026 was
  AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, and they build cleanly together with compileSdk 37.
  The project handoff pointed at "`platform-currency.md` §10" for choosing dependencies; the file
  ends at §9.
- **Proposed text:** add the dependency-choice section the handoff expected, with a procedure
  that does not depend on a web page being current:
  - read `maven-metadata.xml` straight from Maven Central or Google Maven for each artifact
    (`<release>` can be a pre-release, so take the newest version that is digits and dots only);
  - read `https://services.gradle.org/versions/current` for Gradle;
  - prove the set by building an empty multi-module project before writing code.
- **Evidence:** `gradle/libs.versions.toml` header; D-020.

### 4. Documentation pages lag the artifacts

- **File:** `ecosystem.md`.
- **Observed:** the ML Kit GenAI Prompt "get started" page showed `1.0.0-beta4`; Google Maven had
  `1.0.0-beta5`. The exact API (method names, suspend or not, error-code constants) was settled
  by unpacking the AAR and running `javap` on `classes.jar`, which took a minute and removed all
  guessing. The same worked for LiteRT-LM, where there was no usable reference page.
- **Proposed text:** before writing against an unfamiliar or beta library, read its signatures
  from the artifact Gradle actually resolved (`javap -cp classes.jar <class>`), not from a web
  page or from memory.

## Additions: the skill is silent

### 5. Building a library, not an app

- **File:** `SKILL.md` and `lifecycle.md`.
- **Observed:** the skill's opening instruction is "run the intake interview, do not scaffold".
  For a library whose repository already holds a design, architecture and roadmap, the interview,
  design phase and `verify-install.sh` do not apply, and nothing says what does.
- **Proposed text:** a short library path. Its gates:
  - the spec is the repository's own docs;
  - an empty multi-module build is proven first;
  - the pure-Kotlin core gets a build guard against Android and network imports, shown failing
    on a planted import;
  - the sample app is the thing `verify-install.sh` and journeys run against;
  - the Maven namespace is asked for before the first source file, because it becomes every
    package name.

### 6. Building on a machine with only Android Studio's JDK

- **File:** `windows-toolchain-and-emulators.md`.
- **Observed:** no `java` on `PATH`; the only JDK was Android Studio's bundled one, version 25.
  Setting `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` and compiling to Java 17
  bytecode with `-Xjdk-release=17` (Kotlin) and `options.release = 17` (Java) worked without
  installing anything. CI on a real JDK 17 then confirmed it.
- **Proposed text:** that recipe, plus bootstrapping a Gradle wrapper with no Gradle installed:
  download the distribution, check it against the published `.sha256`, run
  `gradle wrapper --gradle-version X --gradle-distribution-sha256-sum <hash>`.

### 7. "BUILD SUCCESSFUL" does not mean the tests ran

- **File:** `testing-and-bugs.md`.
- **Observed, twice:**
  - With the Gradle build cache on, a full `clean build` reported success in 2 seconds with every
    test task restored from cache.
  - JUnit silently ignores a test function that does not return `Unit`. Two tests written as
    `fun x() = runBlocking { … assertIs<T>(…) }` returned the asserted value and never ran. They
    were the two most important tests in the module (download survives a restart; checksum
    mismatch). Nothing was reported as skipped.
- **Proposed text:** after any test run that counts as proof, use `--rerun-tasks` and read the
  counts from the XML reports; compare the number of `@Test` annotations in each file with the
  number executed; write `= runBlocking<Unit> { … }` or `= runTest { … }`.

### 8. Showing a guard fail, as a routine

- **File:** `testing-and-bugs.md` (the house rule "every guard is shown failing" has no method).
- **Observed:** 20 planted violations were applied one at a time: commit, edit one line with
  `sed`, run the tests, confirm the named test fails, `git checkout -- .`. It took seconds each
  and found tests that could not fail, for example an assertion placed inside a callback whose
  exceptions the library deliberately swallows.
- **Proposed text:** that loop, and the rule that an assertion never lives inside code whose
  failures the system under test is designed to absorb: record a flag there and assert outside.

### 9. Fakes hide real failures: keep one test with the real artifact

- **File:** `testing-and-bugs.md`.
- **Observed, three times in one project:**
  - A fake 8 MB "model" passed every download test. The first real model verified its SHA-256
    and then failed to load, because the runtime identifies the format by file extension and the
    library saved it as `.model`.
  - A hand-written fake streaming server stopped streaming immediately (in Node,
    `req.on("close")` fires when the request body ends, not when the client disconnects; it must
    be `res.on("close")`). Every journey step failed and the library looked broken.
  - The first run against a real LAN server met a reasoning model that streams only reasoning for
    28 seconds to over two minutes before its first word. No fake had that shape, and it needed a
    new event type in the public API.
- **Proposed text:** every integration point gets at least one opt-in test against the real
  thing; a one-token reply does not prove streaming (ask for a longer answer and require several
  chunks); and check a fake with `curl` before blaming the code under test.

### 10. Scripted journeys without UI automation

- **File:** `testing-and-bugs.md` and `lifecycle.md` Phase 4.
- **Observed:** the sample app reads every setting from launch extras and writes one content-free
  `RESULT …` line to logcat. A bash script can then drive nine scenarios with `adb shell am
  start`, wait for the line, and assert on it, with no UI Automator and no flakiness from layout.
  Things that mattered:
  - set `ANDROID_SERIAL` and say so in the script header (see 11);
  - `am start --es key "two words"` is split by `adb shell`; pass the whole command as one quoted
    string and escape the spaces;
  - `am force-stop` does **not** cancel `DownloadManager` downloads, which makes it a good way to
    test that a download survives the app being killed;
  - `cmd connectivity airplane-mode enable|disable` gives a real offline state, and adb keeps
    working;
  - a scenario's expectation must not depend on leftover device state (a later journey left a
    model on the device and changed an earlier journey's result).
- **Proposed text:** this pattern as an alternative to `*.journey.xml` for libraries and
  network-heavy features, with those five notes.

### 11. More than one emulator, more than one session

- **File:** `windows-toolchain-and-emulators.md` and `subagent-delegation.md`.
- **Observed:** halfway through a journey two more emulators appeared, started by a different
  Claude session working on another project. Every later `adb` call failed with "more than one
  device/emulator" and three steps failed for no reason to do with the code.
- **Proposed text:** start your own emulator on an explicit port (`-port 5560`), export
  `ANDROID_SERIAL` before any `adb` or Gradle `connected*` task, shut down only the emulator you
  started (`adb -s <serial> emu kill`), and never install on or stop one you did not start.

### 12. What the stock emulator can and cannot prove

- **File:** `windows-toolchain-and-emulators.md`.
- **Observed:**
  - Gemini Nano reports `UNAVAILABLE` on the emulator; there is no way to test it without a
    supported phone.
  - LiteRT-LM ships an `x86_64` native library, so real on-device inference does run on the
    emulator: a 347 MB model loaded in about 2 seconds and answered at a usable speed.
  - The emulator's default network is unmetered Wi-Fi, so "Wi-Fi only" download rules pass
    without setup.
- **Proposed text:** a short table of "needs real hardware" versus "fine on the emulator" for
  on-device AI, so a blocked item is declared early with its exact missing piece.

### 13. Secrets for opt-in tests

- **File:** `permissions-storage-cloud.md` or `testing-and-bugs.md`.
- **Observed:** tests against real endpoints read their URL, model and key from a git-ignored
  `.env` in the repository root, loaded by Gradle into the test process only. A committed
  `.env.example` documents the names. Key values are never made Gradle task inputs (only whether
  they are set), so they cannot reach the build cache or a build scan. Diagnostics print variable
  names, counts and timings, never values or model output.
- **Proposed text:** that pattern, with the `build.gradle.kts` snippet from `openai/`.

### 14. Network clients that carry user data

- **File:** `permissions-storage-cloud.md`.
- **Observed:** an independent review found that OkHttp's defaults would have re-sent a prompt,
  with approved personal data, to whatever host a `307` pointed at, and would silently retry a
  request that was not safe to send twice.
- **Proposed text:** for any client that sends user data to a user-configured host: turn off
  `followRedirects`, `followSslRedirects` and `retryOnConnectionFailure`; never put response
  bodies or exception messages into errors or logs (type names and status codes only); and note
  that a network security config cannot express "private addresses only", so plain HTTP to a home
  server needs `usesCleartextTraffic` plus a check on the connected address.

### 15. Reviews anchored on invariants find real things

- **File:** `subagent-delegation.md`.
- **Observed:** one read-only review agent, given the three invariants verbatim, a list of
  failure classes to hunt, and an instruction to report only defects with a concrete trigger,
  returned 12 findings, 10 of them real, including the redirect leak above. It also named three
  tests that asserted less than their names claimed.
- **Proposed text:** keep that brief as a template: invariants verbatim; "do not edit or run
  anything"; "each finding needs file, line, trigger and wrong outcome"; "label CONFIRMED or
  PLAUSIBLE"; "say which areas are clean"; a word limit.

### 16. Stacked pull requests and late fixes

- **File:** `lifecycle.md` or `releasing.md`.
- **Observed:** one branch and one pull request per milestone were stacked T1 ← T2 ← T3. Two
  later changes that belong to T1 (the stricter contract test, the thinking event) were committed
  on the T3 branch because it was the tip. Nothing broke, but T1's pull request no longer shows
  all of T1.
- **Proposed text:** decide the policy before stacking: either late fixes go on the base branch
  and are merged up the stack, or the pull requests say plainly that the tip carries fixes for
  earlier milestones.

### 17. Windows shell traps

- **File:** `windows-toolchain-and-emulators.md`.
- **Observed:**
  - Git Bash heredocs and `node -e` bodies containing an apostrophe failed to parse; long commit
    messages and pull-request bodies had to go through files (`git commit -F`, `--body-file`).
  - `python` on a stock machine is only the Microsoft Store stub; `node` was the scripting
    runtime that was actually there.
  - `/tmp` in Git Bash is not a path Node can open.
  - `gradlew` needs `* text=auto eol=lf` in `.gitattributes` and
    `git update-index --chmod=+x gradlew`, or Linux CI cannot run it.
  - `local.properties` must use forward slashes; a backslash before `U` is read as an escape.
- **Proposed text:** add these to the Windows section.

### 18. Smaller build notes for AGP 9

- **File:** `platform-currency.md` §3.
- **Observed:**
  - With built-in Kotlin, `kotlin { explicitApi(); compilerOptions { … } }` works in an Android
    library module with no Kotlin plugin applied.
  - JUnit 5 in JVM unit tests needs `testOptions { unitTests.all { it.useJUnitPlatform() } }`.
  - `compileSdk = 37` resolves a platform installed as `android-37.0`.
  - With warnings as errors, any `@ExperimentalCoroutinesApi` use fails the build; a library
    should avoid those APIs anyway.
  - OkHttp's Android artifact was not exercised in JVM unit tests: the pure rule was tested on
    the JVM and the interceptor in an instrumented test.

## Raised elsewhere, relevant here

Pete noted, from another project's session, that secret and password fields should work with
password managers (generate, store, restore) and that an emulator a person must interact with
should be opened as a window, placed and sized sensibly for the host screen. This session ran its
emulators headless and did not test either point. For the record of what would need checking
here: the sample app's cloud-key field sets a password input type but no autofill hint, so a
password manager is not told what it is.

## Not lessons, but worth a line in the skill

- Hugging Face's API answers the three questions needed before downloading a model without
  downloading it: `gated` (is there a licence click-through), file sizes, and the SHA-256
  (`lfs.oid` in the tree listing). Pin the URL to a commit, not to `main`.
- A screenshot read back by the agent caught two things logs did not: log lines in a confusing
  order, and a prompt mangled by shell quoting.
