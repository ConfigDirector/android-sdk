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
val darkMode by remember(client) { client.values("dark-mode", false) }
    .collectAsStateWithLifecycle(false)
```

`values` is a Kotlin-only extension: it hands back a `Flow` built on the same listener registration
Java calls, emitting the config's current value straight away and then every change. Consecutive
identical values are not re-emitted, so a config that did not change does not recompose anything.

**`remember` is not optional.** `values` builds a new `Flow` each time it is called, and
`collectAsStateWithLifecycle` subscribes per flow instance — without `remember` every recomposition
would tear down the subscription and start another one.

The default passed to `values` and the one passed to `collectAsStateWithLifecycle` are the same
value for a reason: the first is what the config falls back to, the second is what the composable
shows in the frame before the flow's first emission arrives.

There is one overload of `values` per type a config can be read as — `Boolean`, `String`, `Int`,
`Double` — so a default of any other type is a compile error rather than a failure at runtime.

## Readiness and context come from events

```kotlin
LaunchedEffect(client) {
    client.events.collect { event ->
        when (event) {
            is ClientEvent.Ready -> isReady = true
            is ClientEvent.ContextUpdated -> context = event.context
            is ClientEvent.ConfigsUpdated -> Unit
        }
    }
}
```

`ClientEvent` is a sealed class, so the `when` is exhaustive and a new event kind would be a compile
error here rather than something silently ignored.

Switching identity calls `updateContext` from `rememberCoroutineScope()`. It is a suspend function,
and the screen sets `isReady = false` before it because the client is genuinely not ready while it
reconnects.

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

## It will move onto the Compose artifact

The SDK will grow a second artifact, `configdirector-android-compose`, holding Compose bindings over
the same client. This app switches to it then, and the flow-into-state plumbing above becomes the
binding's job rather than the app's.
