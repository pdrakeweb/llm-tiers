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
