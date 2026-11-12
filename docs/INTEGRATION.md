# Integrating llm-tiers into an Android app

A step-by-step guide, in the order to do things. Every code block is taken from the working
example in [`examples/compose-hilt`](../examples/compose-hilt) (Jetpack Compose for the UI, Hilt
for dependency injection). When in doubt, read the file named above each block: it compiles and
its tests pass.

Terms used here: a **provider** is one place a request can run (a remote endpoint, Gemini Nano, a
downloaded model). **Hilt** is Android's standard dependency-injection library: it builds objects
and hands them to the classes that need them. A **ViewModel** holds a screen's state across
rotation.

## Before you start

- Android Gradle Plugin 9.x, Kotlin 2.4.x, JDK 17 or newer, `minSdk` 26 or higher.
- The library is **not on Maven Central yet**. Publish it to the local Maven cache once:

  ```bash
  ./gradlew publishToMavenLocal
  ```

  run in an `llm-tiers` checkout. Repeat after pulling a newer version.

## The three rules you must not break

The library enforces these, but an integration can still defeat them. Check your work against
each one before you call it done.

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

What that means for your code:

- Put user data in `Request.data` as `Item`s tagged `Public`, `Personal` or `Identifier`. Do not
  paste personal data into the prompt text: the library cannot see inside a string, so it cannot
  protect it.
- Do not log prompts, answers or `Item` values yourself. The library's `Logger` gives you provider
  ids, timings and failure types, which is what you need to debug routing.
- Do not pass the library an `OkHttpClient` that has a body-logging interceptor.
- Handle all five results. Use an exhaustive `when` with no `else`, so a result you forgot is a
  compile error.
- Do not wrap `generate` or `stream` in your own retry loop or timeout. Fallback and timeouts are
  the library's job; a second layer double-sends requests.
- When the user picks a specific provider, pass it as `pin`. Do not implement "use only this one"
  by reordering.

## Step 1 · Dependencies

`settings.gradle.kts`: add the local Maven cache, limited to this library's group.

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("io.github.pdrakeweb") } }
        google()
        mavenCentral()
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.pdrakeweb:llm-tiers-core:0.1.0-SNAPSHOT")           // always
    implementation("io.github.pdrakeweb:llm-tiers-openai:0.1.0-SNAPSHOT")         // remote endpoints
    implementation("io.github.pdrakeweb:llm-tiers-android:0.1.0-SNAPSHOT")        // network monitor, Nano, downloaded models
    implementation("io.github.pdrakeweb:llm-tiers-model-manager:0.1.0-SNAPSHOT")  // only if users download models
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("io.github.pdrakeweb:llm-tiers-testing:0.1.0-SNAPSHOT")    // fakes for your tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
```

`llm-tiers-android` brings both on-device runtimes with it and adds about 60 MB to a debug APK,
even if the app only uses Gemini Nano. There is no lighter option today (D-024 in
`DECISIONS.md`). An app that only uses remote endpoints can depend on `core` and `openai` alone
and implement the small `NetworkMonitor` interface itself.

## Step 2 · Manifest

`app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- Only if users may point the app at a server on their home network (Android 17+). -->
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />

<application android:usesCleartextTraffic="true" …>
```

- You do not add `ACCESS_NETWORK_STATE`: `llm-tiers-android` declares it and the build merges it
  into your manifest.
- `usesCleartextTraffic="true"` is needed only for plain-HTTP home servers. With it, you **must**
  build your client with `privateCleartextOnly()` (step 3), which refuses plain HTTP to anything
  but a private or local address. If your app only uses HTTPS endpoints, leave the attribute out.
- Do not request `ACCESS_LOCAL_NETWORK` at startup. Ask from the screen where the user enters a
  home-network address (step 7).

## Step 3 · One `TieredAi` for the whole app

Create it once and keep it for the life of the process. The library learns each provider's
health and timings; a new instance per screen or per request throws that away.

`examples/compose-hilt/app/src/main/kotlin/…/Ai.kt`:

```kotlin
fun interface AiStreams {                       // what screens depend on; tests replace it
    fun stream(request: Request): Flow<Event>
    suspend fun generate(request: Request): Result = stream(request).filterIsInstance<Event.Completed>().first().result
}

@Singleton
class AiGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val monitor: AndroidNetworkMonitor,
    private val http: OkHttpClient,
    private val approvals: ApprovalBroker,
    private val models: ModelManager,
) : AiStreams {
    private val nano = GeminiNano()
    private var built: Pair<AiSettings, TieredAi>? = null

    override fun stream(request: Request): Flow<Event> = ai().stream(request)

    @Synchronized
    private fun ai(): TieredAi {
        val current = settings.load()
        built?.let { (was, ai) -> if (was == current) return ai }   // rebuild only when settings change
        return build(current).also { built = current to it }
    }

    private fun build(s: AiSettings): TieredAi = TieredAi {
        // Registration order is the default order of preference.
        if (s.remoteUrl.isNotBlank()) {
            val remote = OpenAiCompatible("remote", s.remoteUrl, s.remoteModel, apiKey = s.apiKey, client = http)
            provider(if (isHomeNetworkUrl(s.remoteUrl)) remote.requiringLocalNetworkPermission(context) else remote, quality = 0.9)
        }
        provider(nano, quality = 0.3)

        network = monitor
        approve { request, provider -> approvals.ask(request, provider) }
        logger = Logger { Log.i("llm-tiers", "${it.event} ${it.provider} ${it.outcome} ${it.elapsedMillis}ms") }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides @Singleton fun networkMonitor(@ApplicationContext context: Context) = AndroidNetworkMonitor(context)
    @Provides @Singleton fun httpClient(): OkHttpClient = OkHttpClient.Builder().privateCleartextOnly().build()
    @Provides @Singleton fun modelManager(@ApplicationContext context: Context) = ModelManager(context)
    @Provides fun aiStreams(gateway: AiGateway): AiStreams = gateway
}
```

Notes:

- **Remote providers** are all `OpenAiCompatible(id, baseUrl, model, apiKey = …)`. The base URL
  includes the version path, for example `https://openrouter.ai/api/v1` or
  `http://192.168.1.20:4000/v1`. An HTTPS URL requires a key; a plain-HTTP URL does not and is
  probed for a second before each use, so being away from home costs one second, not a timeout.
- **`quality`** (0 to 1) is your judgement of each provider's answers. It matters only if you use
  quality ranking, `FastThenBest` or `BestOf` (step 6).
- **`network = monitor`** is what makes airplane mode work: with no network, remote providers are
  skipped and on-device ones are tried. Without it the library assumes it is online.
- **`GeminiNano()`** needs no setup. On phones without it, it reports "not ready" and is skipped.
- **Changing settings** builds a new `TieredAi`, which starts with no learned health or timings.
  That is intended: the old figures described the old endpoint.
- Without Hilt: hold the same `AiGateway` in your `Application` class. The rule is one instance.

## Step 4 · Where the API key lives

The library takes the key as a `String`. Storing it is the app's job. The example keeps it
encrypted with a key held in the Android Keystore (a system store that apps cannot read out of),
with no extra dependency: see `KeyVault` in
`examples/compose-hilt/app/src/main/kotlin/…/Settings.kt` and copy it.

For the text field, mark it as a password so a password manager can fill and save it:

```kotlin
OutlinedTextField(
    key, { key = it },
    Modifier.semantics { contentType = ContentType.Password },
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
)
```

Never put a key in source, in `BuildConfig`, or in a log line.

## Step 5 · Asking before personal data leaves the device

The `approve` callback is a suspend function: the library waits in it while your UI asks the
user. It is called once per request per remote provider, only when the request carries `Personal`
items, and never for on-device providers. Bridge it to the screen like this (`Ai.kt`):

```kotlin
@Singleton
class ApprovalBroker @Inject constructor() {
    class Pending(val items: List<String>, val provider: String, private val reply: CompletableDeferred<Boolean>) {
        fun answer(allow: Boolean) { reply.complete(allow) }
    }
    val pending = MutableStateFlow<Pending?>(null)

    suspend fun ask(request: Request, provider: ProviderInfo): Boolean {
        if (request.deadline != null) return false     // an instant job: see "Deadlines and approval"
        val items = request.personalItems
        val reply = CompletableDeferred<Boolean>()
        val question = Pending(items.map { it.value }, provider.id.value, reply)
        pending.value = question
        try { return reply.await() } finally { pending.compareAndSet(question, null) }
    }
}
```

and show it (`MainActivity.kt`):

```kotlin
val approval by approvals.pending.collectAsStateWithLifecycle()
approval?.let { question ->
    AlertDialog(
        onDismissRequest = { question.answer(false) },
        title = { Text("Send to ${question.provider}?") },
        text = { Text("This will leave your device:\n\n" + question.items.joinToString("\n") { "• $it" }) },
        confirmButton = { TextButton({ question.answer(true) }) { Text("Send") } },
        dismissButton = { TextButton({ question.answer(false) }) { Text("Don't send") } },
    )
}
```

Show exactly `request.personalItems`: that is exactly what will be sent. If you configure no
`approve` callback, personal data simply never goes to a remote provider. An unanswered question
counts as "no" after two minutes.

**Deadlines and approval.** A request's `deadline` covers everything, including the time the
library spends waiting inside `approve`. A request with an 800 ms deadline and a `Personal` item
would put up a dialog nobody can answer before the request ends as `NoVerdict`. For such instant
jobs, do one of these:

- send them with `mode = Mode.OnlyOnDevice`, so no approval is ever needed; or
- have `approve` answer at once when `request.deadline != null`: `false` (as the example does),
  or an answer the user already gave for the same data and provider.

Never show an approval dialog for a request that has a deadline.

**A refusal does not always mean `Blocked`.** `Blocked` is the result only when privacy excluded
a provider and no provider was tried. If the user refuses a remote provider and an on-device one
is configured, the request goes on to it without asking (data stays on the device), and the
result is whatever that provider gives. The refused provider appears in `attempts` as
`Skipped(ApprovalDenied)`.

## Step 6 · Making a request and showing every outcome

`examples/compose-hilt/app/src/main/kotlin/…/ChatViewModel.kt`:

```kotlin
val request = Request(
    messages = listOf(user(prompt)),
    data = listOf(Item(note, Sensitivity.Personal, "note")),   // data the user may not want to leave the device
    safeToRetry = true,   // generating text has no side effects; see below
)
job = viewModelScope.launch {
    ai.stream(request).collect { event ->
        when (event) {
            is Event.Started -> Unit
            is Event.Thinking -> _state.update { it.copy(thinking = true) }
            is Event.Token -> _state.update { it.copy(answer = it.answer + event.text, thinking = false) }
            is Event.FellBack -> if (event.discardPartial) _state.update { it.copy(answer = "") }
            is Event.Upgrading -> _state.update { it.copy(answer = "") }
            is Event.Completed -> _state.value = finished(event.result)
        }
    }
}
```

```kotlin
private fun finished(result: Result): ChatState = when (result) {
    is Result.Answer -> ChatState(answer = result.text, status = "Answered by ${result.answeredBy}" + …)
    is Result.Answered -> ChatState(answer = result.shortCircuit.toString(), status = "Answered by the app itself")
    is Result.Blocked -> ChatState(status = "Not sent: nothing was allowed to receive your personal note.")
    is Result.NoVerdict -> ChatState(status = "No answer in time.")
    is Result.NoneAvailable -> ChatState(status = result.attempts.firstNotNullOfOrNull(::advice) ?: "No AI is available right now.")
}
```

Rules for this code:

- **Tokens are a preview; `Result.Answer.text` is the answer.** On `Completed`, replace what is on
  screen with `result.text`. Clear the screen on `FellBack` with `discardPartial` and on
  `Upgrading`.
- **`result.incomplete`** is non-null when the provider failed part-way: the text is partial. Say so.
- **`safeToRetry`**: set it to `true` for ordinary text generation. Without it, the library will
  not move to another provider after a timeout (it cannot know the first one did not act on the
  request), and no concurrent strategy applies. Leave it `false` only for a request that would do
  harm if it ran twice.
- **Cancel by cancelling the coroutine.** The library cancels the provider. Starting a new request
  while one is running: cancel the old job first.
- **Tell the user what to do.** `result.attempts` lists every provider tried or skipped and why.
  Map the failures a user can fix (`KeyMissing`, `AuthRejected`,
  `LocalNetworkPermissionDenied`, `Offline`) to a sentence; `ChatViewModel.advice` shows how.
- If you do not need streaming: `ai.generate(request)` returns the same `Result`. The example's
  `AiStreams` has it as a default method, so a test still builds one with `AiStreams(ai::stream)`.
- **How `data` reaches the model.** The library folds the items a provider is allowed to see into
  the last user message, ahead of your text. `Item(value, sensitivity, label)`: the optional
  third argument is a label. For a prompt `"Summarise the note in one sentence."` and
  `Item("Buy milk on Friday", Sensitivity.Personal, "note")` the model receives:

  ```
  Context:
  - note: Buy milk on Friday

  Summarise the note in one sentence.
  ```

  So write the prompt to refer to the item by its label. Without a label the line is `- <value>`.
  `Identifier` items are dropped and never appear.

To change how providers are chosen or run, set `mode`, `pin`, `rank` or `strategy` on the request.
The table in [`STRATEGIES.md`](STRATEGIES.md) maps what you want to what to set. With nothing
set, `mode` is `Mode.AppOrder` and `strategy` is `Strategy.Sequential`.

### Reference: the types you will touch

| Type | What it is |
|---|---|
| `ProviderId` | A provider's id. `result.answeredBy` and `attempt.provider` are this type; `.value` is the `String` you registered, and `toString()` prints it |
| `Result.Answer` | `text`, `answeredBy: ProviderId`, `tier`, `attempts`, `discardPartial`, `incomplete: ProviderFailure?` |
| `Attempt` | `provider`, `tier`, `outcome`, `why`, `elapsed`, `firstTokenAfter`, `usage` |
| `Outcome` | `Succeeded`, `Failed(failure)`, `Escalated`, `Superseded`, `Skipped(reason)` |

### Reference: every failure (`ProviderFailure`)

| Failure | Meaning | Can the user fix it? |
|---|---|---|
| `Unreachable` | Could not connect; the request was not sent | Check the address or the network |
| `Timeout` | Connected, no reply in time | No |
| `FirstTokenTimeout` | No sign of life within the first-token timeout (10 s by default) | No |
| `Stalled` | Started answering, then went quiet (5 s by default) | No |
| `AuthRejected` | The key was refused | Yes: correct the key |
| `KeyMissing` | A key is required and none is set | Yes: add a key |
| `RateLimited` | The service asked us to slow down | Wait |
| `ContextTooLong(max)` | The prompt is too long for this provider | Shorten it |
| `Server(code)` | The service returned a 5xx error | No |
| `ModelNotReady` | No on-device model is available or loaded | Download a model, if you offer that |
| `Busy` | The on-device model is busy or over its quota | Wait |
| `LocalNetworkPermissionDenied` | Android 17 permission for home-network addresses is missing | Yes: grant it |
| `Other(detail)` | Anything else; `detail` is a type name or status code, never content | No |

### Reference: every skip reason (`SkipReason`)

A skipped provider was never sent anything. `reason.code` is a stable string for display.

| Reason | Meaning |
|---|---|
| `SensitivityNotAccepted(sensitivity)` | The provider is configured not to accept that kind of data |
| `ApprovalDenied` | The user (or your `approve`) said no, or did not answer in time |
| `MissingCapability(capability)` | The request needs `vision`, `jsonSchema` or a longer `context` than it has |
| `ExcludedByMode(mode)` | The request's mode rules it out, for example `OnlyOnDevice` |
| `NotPinned` | The user pinned a different provider |
| `CoolingDown` | It failed recently and is being rested (below). Tell the user to try again shortly |
| `Offline` | There is no network and it is a remote provider |
| `NotSafeToRetry` | An earlier provider may have acted on the request and it is not marked `safeToRetry` |
| `CapabilitiesUnavailable` | The provider did not report what it can do in time |

**Cooldown.** After a failure a provider is rested so later requests do not pay for it again.
`Unreachable`, `ModelNotReady`, `AuthRejected`, `KeyMissing` and
`LocalNetworkPermissionDenied` rest it for 30 seconds at once; `Busy` and `RateLimited` for 10
seconds; three other failures within a minute, 30 seconds. A second request made inside that
window sees `Skipped(CoolingDown)`, not the original failure, so map `CoolingDown` to a message
of its own. After the user fixes something (a key, a permission), call
`ai.health.reset(ProviderId("…"))` so the fix takes effect at once. A pinned provider is always
tried, cooldown or not.

## Step 7 · The local-network permission (home servers only)

From Android 17, an app targeting SDK 37 needs a runtime permission to reach home-network
addresses. Wrapping the provider (`requiringLocalNetworkPermission`, step 3) makes it report
`LocalNetworkPermissionDenied` at once instead of timing out. Ask where the user sets the server:

```kotlin
val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    settings.localNetworkPermissionAnswered()       // calls ai.health.reset(ProviderId("remote"))
}
if (needsPermission) OutlinedButton({ askPermission.launch(ACCESS_LOCAL_NETWORK) }) { Text("Allow access to devices on your network") }
```

`needsLocalNetworkPermission(context)` tells you whether to show the button. Wrap only
providers at home-network addresses: a loopback address (`127.0.0.1`, `localhost`) is exempt
from the permission and must not be gated on it (`isHomeNetworkUrl` in `Ai.kt`). Reset the provider's
health after the user answers, or the library keeps skipping it for the rest of its cooldown.

## Step 8 · Downloaded on-device models (optional)

For phones without Gemini Nano. `OpenModels.all` is a starter catalog; show `displayName` and
`sizeBytes`, show `models.fit(spec)` as advice, and let the user choose.

```kotlin
// registering it (Ai.kt)
provider(LiteRtModel("local", spec.contextTokens) { (models.state(spec) as? ModelState.Ready)?.file }, quality = 0.5)

// download, follow, delete (SettingsViewModel in MainActivity.kt)
models.download(spec)                         // Wi-Fi only unless allowMetered = true
models.observe(spec).collect { state -> … }   // Missing, Downloading(bytes, total), Ready(file), Failed(reason, recoverable)
models.delete(spec)
```

- Call `models.observe(spec)` (or `state`) on every cold start: a download carries on while the
  app is closed, and this is how you pick it back up.
- When the state becomes `Ready`, call `warmUp()` on the provider. Loading takes seconds; without
  it the first request may time out while the model loads.
- When the user picks a different model, `close()` the old `LiteRtModel` and build a new one.
- Model files must be on public HTTPS. Android's download service cannot reach home-network
  addresses.

## Step 9 · Testing your code with the fakes

`llm-tiers-testing` gives you `FakeProvider`, which plays a scripted provider while the real
router runs around it. No network, no device, virtual time.

`examples/compose-hilt/app/src/test/kotlin/…/ChatViewModelTest.kt`:

```kotlin
private fun TestScope.viewModel(vararg providers: FakeProvider, approve: Boolean = true): ChatViewModel {
    val ai = TieredAi {
        providers.forEach(::provider)
        clock = SchedulerClock(testScheduler)
        approve { _, _ -> approve }
    }
    return ChatViewModel(AiStreams(ai::stream))
}

@Test
fun `falls back and says what happened`() = runTest(dispatcher) {
    val vm = viewModel(
        FakeProvider("remote", Tier.REMOTE) { fail(ProviderFailure.RateLimited) },
        FakeProvider("nano", Tier.ON_DEVICE) { token("from the device"); done() },
    )
    vm.send("hi")
    advanceUntilIdle()
    assertEquals("from the device", vm.state.value.answer)
}
```

Script steps: `token("…")`, `tokens("a", "b")`, `done()`, `fail(failure)`, `thinking()`,
`escalate()`, `wait(duration)`, `hang()`, `crash()`. `provider.calls` and `provider.received` tell
you what a provider was sent, which is how you assert that personal data did not leave.

A request with a deadline, in virtual time (`ChatViewModelTest.kt`):

```kotlin
val slow = FakeProvider("nano", Tier.ON_DEVICE) { wait(2.seconds); token("too late"); done() }
val ai = TieredAi { provider(slow); clock = SchedulerClock(testScheduler) }
val result = AiStreams(ai::stream).generate(Request(listOf(user("title?")), deadline = 800.milliseconds))
assertTrue(result is Result.NoVerdict)
assertEquals(800, testScheduler.currentTime)
```

Write at least these tests for your integration: an answer is shown; a failure falls back; a
refused approval sends nothing; each failure and skip reason you map to advice produces that
advice, including `CoolingDown` on the request after a failure; and any request with a deadline
ends at the deadline.

## Checklist before you call it done

- [ ] One `TieredAi` for the process, rebuilt only when settings change.
- [ ] `network = AndroidNetworkMonitor(context)` is set.
- [ ] Remote clients are built with `privateCleartextOnly()` if the manifest allows cleartext.
- [ ] User data is in `Request.data` with a sensitivity, not pasted into the prompt.
- [ ] `approve` shows `request.personalItems` and the provider, and "no" sends nothing.
- [ ] No request with a `deadline` can put up an approval dialog.
- [ ] `CoolingDown` has its own message; health is reset after the user fixes a key or permission.
- [ ] All five results are handled by an exhaustive `when`; `incomplete` is shown.
- [ ] On `Completed` the screen shows `result.text`; `discardPartial` and `Upgrading` clear it.
- [ ] No prompt, answer or item value is logged; no body-logging HTTP interceptor.
- [ ] No retry loop or timeout of your own around the library.
- [ ] The key is stored encrypted and its field is marked as a password.
- [ ] The local-network permission is asked from settings, and health is reset afterwards.
- [ ] Unit tests with `FakeProvider` cover answer, fallback, refusal and your advice messages.
- [ ] The app was launched on a device or emulator and a request was sent.
