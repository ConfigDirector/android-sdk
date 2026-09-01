# ConfigDirector sample apps

Two Android apps showing how to use the ConfigDirector Android client SDK: each reads the same
handful of configs and re-renders as their values change.

[**compose**](compose) is the modern one — Kotlin and Jetpack Compose, reading each config through
the `configdirector-android-compose` bindings, which recompose the screen when a value changes.

[**java**](java) is the other half of the SDK's audience — plain Java, framework views, no AndroidX
and no Kotlin sources at all. It exists to keep the Java surface honest: Kotlin tests cannot tell
whether an API is callable from Java, so if the Java surface breaks, that module stops compiling.

Neither app shares code with the other. They differ in how they consume the SDK, which is the point
of having both.

## Running them

`installDebug` installs onto a device, so one has to be running first — with nothing attached it
fails with `no connected devices`. Start an emulator from Android Studio's Device Manager, or from
a terminal:

```sh
emulator -list-avds
emulator -avd Pixel_10_Pro &
adb wait-for-device
```

`emulator` and `adb` live in the Android SDK rather than on the `PATH` by default:
`~/Library/Android/sdk/{emulator,platform-tools}` on macOS, `~/Android/Sdk/...` on Linux.

Then, from the repository root:

```sh
./gradlew :samples:configdirector-android:compose:installDebug
adb shell am start -n com.configdirector.sample.compose/.MainActivity
```

```sh
./gradlew :samples:configdirector-android:java:installDebug
adb shell am start -n com.configdirector.sample.java/.MainActivity
```

To build an APK without a device involved at all, use `assembleDebug` instead of `installDebug`; it
lands in the module's `build/outputs/apk/debug/`.

Turn the SDK's own logging up by watching its tag, which both apps set to `DEBUG`:

```sh
adb logcat -s ConfigDirector:V ConfigDirectorSample:V
```

## The SDK key

Both apps read the key from `local.properties`, the same git-ignored file the Android SDK location
lives in:

```properties
configdirector.clientSdkKey=YOUR-KEY
configdirector.userId=
configdirector.userName=
configdirector.userRole=
```

They reach the apps as `BuildConfig` fields, so nothing has to be committed. Take the key from your
ConfigDirector dashboard.

Alongside the key sit the values of the identity configs are evaluated against, the same three the
Swift samples read from `Config.local.xcconfig`. `configdirector.userRole` is sent as the `role`
trait. Leave them empty and the Configured identity carries no id, name or trait, so configs are
evaluated without a context.

Without it each app still builds and runs. It connects with a stand-in key the server will not
recognise, says so on screen, and every config falls back to the default passed alongside its key —
which is what a misconfigured app looks like, and worth seeing once.

## What they read

Both apps read the keys of the ConfigDirector sample project. Pointing them at a project without
them is fine: each config falls back to the default the app passes alongside the key, which is what
the screen shows until the client is ready.

| Key                      | Read as | Default the apps pass |
| ------------------------ | ------- | --------------------- |
| `temporary-feature-flag` | boolean | `true`                |
| `permanent-kill-switch`  | boolean | `false`               |
| `integer-config`         | integer | `10`                  |
| `day-of-the-week-config` | string  | `Friday`              |
| `json-value-config`      | string  | `{}`                  |
| `integer-config`         | double  | `0.0`                 |
| `json-value-config`      | map     | empty                 |

These are the keys the other ConfigDirector sample apps read, so the same dashboard project drives
all of them.

`json-value-config` appears twice: read with a string default it serves the raw document, and read
with a map default it serves the parsed one, whose values are String, Number, Boolean, List, Map or
null. `integer-config` appears twice for the same reason — every type the SDK reads is on screen.

Both connect in streaming mode, so a value changed in the dashboard reaches the screen without
restarting them.

The Context row is where targeting shows: each identity calls `updateContext`, which reconnects,
re-evaluates every config against the new identity, and pushes the new values to whatever is
watching — the way to watch a targeting rule take effect without rebuilding.

| Identity     | Context                                                     |
| ------------ | ----------------------------------------------------------- |
| Configured   | the id, name and `role` trait from `local.properties`        |
| Beta tester  | `beta-tester`, Beta Tester, `role: beta`                     |
| Anonymous    | no id or name, anonymous                                     |

These are the identities the other ConfigDirector sample apps offer, so a targeting rule written
against `role` behaves the same everywhere.

## Which SDK they build against

Each depends on the module in this repository it demonstrates:

```kotlin
implementation(project(":configdirector-android-compose"))  // the Compose sample
implementation(project(":configdirector-android"))          // the Java sample
```

The Compose artifact depends on the core and re-exposes it, so the Compose sample gets both from
that one line.

The other ConfigDirector SDKs point their samples at the released artifact and swap in the local
one behind a flag, so a breaking API change fails in a real consumer before it ships. This SDK has
not been published yet, so there is nothing to point at; the samples move to the released artifact
when it is.

## They do not share a minSdk

| Module                  | minSdk | Java bytecode |
| ----------------------- | ------ | ------------- |
| `configdirector-android` | 21     | 8             |
| `samples/.../java`       | 21     | 8             |
| `samples/.../compose`    | 23     | 11            |

The SDK runs on API 21 and ships Java 8 bytecode, for apps that have not moved. Compose cannot:
`androidx.navigationevent`, which `activity-compose` depends on, declares API 23 as its floor, and
AndroidX ships Java 11 bytecode, which will not inline into a Java 8 target. The Java sample is
pinned to the SDK's own floor deliberately — it is the proof that the floor is real.
