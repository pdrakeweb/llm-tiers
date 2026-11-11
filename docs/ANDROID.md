# Android setup

What an app adds to use the `:android` module. Terms: *targetSdk* is the Android version whose
behaviour rules the app follows; a *runtime permission* is one the user grants in a system dialog
while the app runs, not at install.

## Network monitor

```kotlin
val monitor = AndroidNetworkMonitor(context)   // one per process is enough
val ai = TieredAi { network = monitor; /* providers */ }
```

Each network attachment has its own id, so health recorded on one network is not carried to the
next. Coming home makes the LAN provider eligible again without a restart. The library's manifest
declares `ACCESS_NETWORK_STATE`.

## Plain HTTP to a home server

Android blocks plain HTTP by default, and its network security config can only allow it per domain
name, not per address range. So the app allows cleartext at the platform level and lets the
library enforce "private addresses only" on every connection:

```xml
<!-- AndroidManifest.xml -->
<application android:usesCleartextTraffic="true" …>
```

```kotlin
val lanClient = OkHttpClient.Builder().privateCleartextOnly().build()
provider(OpenAiCompatible("home", "http://192.168.1.20:4000/v1", model = "…", tier = Tier.LAN, client = lanClient))
```

`privateCleartextOnly()` checks the address OkHttp actually connected to, after DNS resolution and
before the request is written. Plain HTTP goes through only to loopback, link-local, RFC 1918,
CGNAT (100.64.0.0/10) and IPv6 unique-local addresses, or to a `.local` name, and never through a
proxy. Anything else fails with `CleartextNotAllowedException`, which the router reports as
`Unreachable`. HTTPS is unaffected. Use the same client for every provider so the rule cannot be
skipped by accident.

To keep LAN traffic off cellular, bind the client to Wi-Fi:
`OkHttpClient.Builder().socketFactory(monitor.wifiSocketFactory())`.

## Local network permission (Android 17, targetSdk 37+)

From Android 17, an app that targets SDK 37 or higher needs the `ACCESS_LOCAL_NETWORK` runtime
permission to reach addresses on the local network. Without it connections are dropped silently.

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

```kotlin
provider(OpenAiCompatible(/* LAN */).requiringLocalNetworkPermission(context))

// In the settings screen where the user enters the home server:
if (needsLocalNetworkPermission(context)) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 1)
// After the user answers:
ai.health.reset(ProviderId("home"))
```

While the permission is missing the wrapped provider reports `LocalNetworkPermissionDenied` at
once, the router falls through to the next provider, and the result's attempts tell the app what
to ask for. Do not declare or request the permission while targeting SDK 36 or lower.

## Gemini Nano

```kotlin
val nano = GeminiNano()
provider(nano)
```

Gemini Nano runs on the device through the ML Kit GenAI Prompt API. It needs a phone that ships
it (for example a Pixel 10 Pro) with a locked bootloader, and it only runs while the app is in
the foreground. Elsewhere `probe()` reports `ModelNotReady` and the router moves on. `nano.status()`
and `nano.download()` are for a settings screen. Its context is about 4,000 tokens and it has no
JSON-schema output, so requests that need more are routed past it.

## Downloaded models (LiteRT-LM)

For phones without Gemini Nano, an app can download an open model and run it on the device.

```kotlin
val models = ModelManager(context)                       // :model-manager
val spec = ModelSpec("gemma-text", url = "https://…/model.litertlm", sha256 = "…", sizeBytes = 1_234_567_890)
val local = LiteRtModel("gemma-text") { (models.state(spec) as? ModelState.Ready)?.file }
provider(local)
```

- **Show the size first.** `spec.sizeBytes` is known before anything is fetched.
- **Download.** `models.download(spec)` hands the job to Android's system `DownloadManager`, so it
  carries on when the user leaves the screen or the app is killed, and resumes after a dropped
  connection. It waits for Wi-Fi unless called with `allowMetered = true`.
- **Follow it.** `models.observe(spec)` emits `Missing`, `Downloading(bytes, total)`, then
  `Ready(file)` or `Failed(reason, recoverable)`. Call `models.state(spec)` on every cold start.
- **Verified.** A finished download must match the spec's size and SHA-256 or it is deleted and
  reported as `SizeMismatch` or `ChecksumMismatch`. A file the library did not verify is never
  used.
- **Delete.** `models.delete(spec)` cancels any download and removes the file.

Loading a model takes seconds. Call `local.warmUp()` when the app opens; otherwise the first
request may time out while the model loads (the load carries on, and the next request is fast).
The provider runs one request at a time and reports `Busy` to a second.

Host the model on a public HTTPS URL. On Android 17 the system `DownloadManager` cannot reach
local-network addresses at all (the download stays pending without an error), so a model served
from a home server will not download.

## Sample app and journey

`:sample` is a single screen: provider settings, a prompt box, and a log of where each request ran
and why. `sample/journey/journey.sh` drives it on an emulator through nine steps (permission
missing, LAN up, LAN gone, LAN silent, LAN back, pinning, cleartext rule, approval, offline) using
two fake endpoints on the development computer.

```bash
./gradlew :sample:installDebug
```

```bash
bash sample/journey/journey.sh
```

`sample/journey/journey-models.sh` covers downloads with a fake 8 MB file: the download survives
the app being killed, a wrong checksum is reported, the model can be deleted, and a file that is
not a real model gives `ModelNotReady` rather than a crash.

```bash
bash sample/journey/journey-models.sh
```
