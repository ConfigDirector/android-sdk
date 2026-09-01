# ConfigDirector Android SDK

This is the Android client SDK for [ConfigDirector](https://www.configdirector.com). It is written
in Kotlin and is meant to be used from Kotlin and Java alike, and it ships as two artifacts:

- `com.configdirector:configdirector-android` — the SDK.
- `com.configdirector:configdirector-android-compose` — optional Jetpack Compose bindings over it.

## Documentation

Refer to the
[official documentation for the Android SDK](https://docs.configdirector.com/sdks/mobile/android).

There is also
[a quickstart guide for ConfigDirector and any of our SDKs](https://docs.configdirector.com/getting-started/quickstart).

## Sample apps

[`samples/configdirector-android/`](samples/configdirector-android/) holds two Android apps built on
this SDK, reading the same handful of configs and re-rendering as their values change.
[**compose**](samples/configdirector-android/compose) is Kotlin and Jetpack Compose;
[**java**](samples/configdirector-android/java) is plain Java with framework views, no Kotlin
sources at all.

They build against this checkout rather than a released version. From the repository root, with a
device or emulator running:

```sh
./gradlew :samples:configdirector-android:compose:installDebug
```

See [`samples/configdirector-android/README.md`](samples/configdirector-android/README.md) to point
them at your own ConfigDirector project.

## Getting Help

Reach out to us via https://www.configdirector.com/support
