# ConfigDirector sample apps

Two Android apps showing how to use the ConfigDirector Android client SDK: each reads the same
handful of configs and re-renders as their values change.

[**compose**](compose) is the modern one — Kotlin and Jetpack Compose, where each config is a
`Flow` collected into Compose state.

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

## There is no SDK key yet

Both apps pass `sample-client-sdk-key`, which is not a real key and does not need to be.

The SDK's transports are not implemented yet, so the client talks to a stubbed transport that
serves a hard-coded config set — no network, no dashboard, any non-blank key. The apps become real
consumers, reading a key from your ConfigDirector dashboard, once the transports land. The client
rejects a blank key today, and will reject an invalid one then; the "Every API" button in the Java
sample shows that rejection.

## What the stub serves

| Key               | Type    | Value                                                       |
| ----------------- | ------- | ----------------------------------------------------------- |
| `dark-mode`       | boolean | `true` for a `plan: pro` context, `false` otherwise          |
| `welcome-message` | string  | `Hello, <the context's name>`                                |
| `max-items`       | integer | `25` for `plan: pro`, `10` otherwise                         |
| `sample-rate`     | float   | `0.25`                                                       |
| `theme`           | json    | `{"primary":"#101010"}`                                      |
| `beta-banner`     | boolean | served with no value, so every read falls back to its default |

The values depend on the context the way a server's evaluation of targeting rules does. That is
what makes the Context row worth pressing: each identity calls `updateContext`, which reconnects,
re-evaluates every config, and pushes the new values to whatever is watching.

| Identity     | Context                                            |
| ------------ | -------------------------------------------------- |
| Configured   | `user-123`, Sam, `plan: free`                       |
| Pro plan     | `user-456`, Ada, `plan: pro`, `seats: 12`, `beta: true` |
| Anonymous    | no id or name, anonymous                            |

`beta-banner` is the one row on screen that is always a fallback: the config exists, the context
resolves no value for it, so both apps show the default they passed in. Every other row is a value
the server chose.

## Which SDK they build against

Both depend on the module in this repository:

```kotlin
implementation(project(":configdirector-android"))
```

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
