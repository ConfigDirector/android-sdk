# Compose sample

A Jetpack Compose app reading six configs, each as a `Flow` that emits its current value and then
every value it changes to. See [the sample overview](../README.md) for what the configs are, what
the three identities carry, and how to run this.

## The client belongs to the application

[`SampleApplication`](src/main/kotlin/com/configdirector/sample/compose/SampleApplication.kt) builds
one client, initializes it during startup, and shares it with every screen. One client per app, not
one per screen: each one opens its own connection and starts out not-ready, so a per-screen client
would serve defaults most of the time.

Nothing closes it. It lives as long as the process, and Android reclaims everything when the
process ends — `Application.onTerminate` only runs on an emulator. An app that wants the client
gone earlier, on sign-out for instance, calls `close()` itself.

## Reading a config

```kotlin
val featureFlag = configValue("temporary-feature-flag", true)
```

`configValue` comes from `configdirector-android-compose`, the Compose artifact. It subscribes to
the config, returns its current value, and recomposes this screen whenever that value changes —
from an edit in the dashboard, or from a context update. Until the client is ready, and for a value
that cannot be read as this type, it returns the default it was given.

There is one overload per type a config can be read as, so a default of any other type is a compile
error rather than a failure at runtime. `json-value-config` is read twice below, once with a
`String` default for the raw document and once with a `Map` default for the parsed one.

The binding does the subscribing, so there is no `remember` to get wrong here. Reading the same
config from the core artifact directly means `remember(client) { client.values(key, default) }` and
collecting it yourself — the Compose artifact exists to make that unnecessary.

## The client comes from the composition

[`MainActivity`](src/main/kotlin/com/configdirector/sample/compose/MainActivity.kt) wraps the
content in `ConfigDirectorProvider(client)`, and every binding reads from there rather than being
handed the client. A binding takes an explicit `client` parameter too, for a screen that has one
without a provider above it.

## Readiness and context are bindings as well

```kotlin
val isReady = isClientReady()
val context = configContext()
```

Both follow the client's own events, so the header switches from `Connecting…` to `Ready` and the
context line updates when an identity change takes effect, without this screen collecting events
itself.

Switching identity calls `updateContext` from `rememberCoroutineScope()`. It is a suspend function,
and the screen sets `isReady = false` before it because the client is genuinely not ready while it
reconnects.

## Tests

[`SampleScreenTest`](src/test/kotlin/com/configdirector/sample/compose/SampleScreenTest.kt) renders
`SampleScreen` under Robolectric with `createComposeRule`, wrapped in the production
`ConfigDirectorProvider` exactly as `MainActivity` does, but with the client of a **test client**
from `configdirector-android-testing`: the SDK's real client over an in-memory connection the test
controls. Robolectric is told to run a plain `Application` instead of `SampleApplication`, so no
real client is built and nothing connects.

```kotlin
private val testClient = createTestClient(values = SAMPLE_VALUES)

compose.setContent { ConfigDirectorProvider(testClient.client) { SampleScreen() } }
runBlocking { testClient.client.initialize(SampleUser.CONFIGURED.context) }
```

The tests cover what the screen does with the client: the defaults and `Connecting…` until the
client is ready, every row once it is, a row re-rendering after `testClient.setValue`, the default
coming back after `testClient.removeValue`, the identity chips calling `updateContext` (visible in
`testClient.contextUpdates` and in the context line), a held initialization, and a failed one.

The SDK hands values to the bindings on the main looper, which Robolectric only runs when the test
lets it, so after `initialize` and after every change the test idles the looper and waits for the
composition to settle:

```kotlin
shadowOf(Looper.getMainLooper()).idle()
compose.waitForIdle()
```

Run them with `./gradlew :samples:configdirector-android:compose:testDebugUnitTest -PuseLocalSdk`.

## Two things this module does not inherit from the SDK

**minSdk 23 and Java 11 bytecode**, where the SDK itself is 21 and Java 8. Compose forces both; see
[the overview](../README.md#they-do-not-share-a-minsdk).

**The Compose compiler plugin is pinned by hand** in
[`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml), to the Kotlin version the Android
Gradle plugin uses for its built-in Kotlin support. AGP 9 compiles Kotlin itself rather than through
the `kotlin-android` plugin, so there is no Kotlin version in the build files for the Compose plugin
to follow — the two have to be kept in step deliberately.

## Edge to edge

The app targets SDK 37, where edge-to-edge is enforced, so the screen keeps clear of the system bars
itself with `Modifier.safeDrawingPadding()`. Without it the content draws underneath the status bar.
