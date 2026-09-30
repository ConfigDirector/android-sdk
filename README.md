# ConfigDirector Android SDK

[![CI][ci-badge]][ci] [![Maven Central][maven-badge]][maven]

Android SDK for [ConfigDirector](https://www.configdirector.com), remote config and feature flags with typed values, JSON Schema validation, and safe renames of live flags. Start free, no card required.

It is written in Kotlin and is meant to be used from Kotlin and Java alike, and it ships as three artifacts: `com.configdirector:configdirector-android`, the SDK; `com.configdirector:configdirector-android-compose`, optional Jetpack Compose bindings over it; and `com.configdirector:configdirector-android-testing`, tools for testing the code that reads your configs.

This repository also holds [`com.configdirector:configdirector-openfeature-android-provider`](configdirector-openfeature-android-provider/), an [OpenFeature](https://openfeature.dev) provider built on the SDK, released on its own.

## Install

```kotlin
dependencies {
    implementation("com.configdirector:configdirector-android:1.5.1")

    // Optional, for Jetpack Compose applications
    implementation("com.configdirector:configdirector-android-compose:1.5.1")

    // For your tests
    testImplementation("com.configdirector:configdirector-android-testing:1.5.1")
}
```

The SDK declares the `INTERNET` permission, which is merged into your application's manifest.

## Retrieve a value

```kotlin
import com.configdirector.ConfigDirectorClient
import com.configdirector.value

val client = ConfigDirectorClient(applicationContext, "YOUR-CLIENT-SDK-KEY")
client.initialize()

val darkMode = client.value("dark-mode", false)
```

Full details are in the [official documentation](https://docs.configdirector.com/sdks/mobile/android).

## Test your code

`configdirector-android-testing` creates a **test client**: the SDK's real client connected to an in-memory server that your test controls. No network connection is opened, no telemetry is sent, and no Android `Context` is needed, so it runs in a plain JVM unit test.

```kotlin
import com.configdirector.testing.createTestClient

val testClient = createTestClient(values = mapOf("dark-mode" to true, "max-items" to 20))
testClient.client.initialize()

val settings = Settings(testClient.client)
assertThat(settings.isDarkMode).isTrue()

testClient.setValue("dark-mode", false)
assertThat(settings.isDarkMode).isFalse()
```

`testClient.client` is a `ConfigDirectorClient`, so it goes anywhere your code accepts one. From Java, `ConfigDirectorTesting.createTestClient(values)`. See [Test your code](https://docs.configdirector.com/sdks/mobile/android#test-your-code) in the documentation for holding and failing initialization, the Compose bindings under test, and what to expect.

## Documentation

Refer to the [official documentation for the Android SDK](https://docs.configdirector.com/sdks/mobile/android).

There is also [a quickstart guide for ConfigDirector and any of our SDKs](https://docs.configdirector.com/getting-started/quickstart).

## Sample apps

[`samples/configdirector-android/`](samples/configdirector-android/) holds two Android apps built on
this SDK, reading the same handful of configs and re-rendering as their values change.
[**compose**](samples/configdirector-android/compose) is Kotlin and Jetpack Compose;
[**java**](samples/configdirector-android/java) is plain Java with framework views, no Kotlin
sources at all.

[`samples/configdirector-openfeature-android-provider/`](samples/configdirector-openfeature-android-provider/)
holds a Compose app reading the same configs through the OpenFeature provider.

They depend on the released artifacts, the way your own app would; `-PuseLocalSdk` builds them
against this checkout instead. From the repository root, with a device or emulator running:

```sh
./gradlew :samples:configdirector-android:compose:installDebug
```

See [`samples/configdirector-android/README.md`](samples/configdirector-android/README.md) to point
them at your own ConfigDirector project.

## Getting Help

- [Ask a question in Discussions](https://github.com/orgs/ConfigDirector/discussions)
- [Contact support](https://www.configdirector.com/support)

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/android-sdk/actions/workflows/configdirector-android.yml/badge.svg
[ci]: https://github.com/ConfigDirector/android-sdk/actions/workflows/configdirector-android.yml
[maven-badge]: https://img.shields.io/maven-central/v/com.configdirector/configdirector-android
[maven]: https://central.sonatype.com/artifact/com.configdirector/configdirector-android
