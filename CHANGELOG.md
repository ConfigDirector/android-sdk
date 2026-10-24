# Changelog

Changes to `com.configdirector:android-sdk`, `com.configdirector:android-sdk-compose`, and
`com.configdirector:android-sdk-testing`. The three are released together and share a version, so
they share this file; an entry says which artifact it belongs to when it is not all of them.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and these artifacts
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.9.0] - 2026-10-24

### Added

- Values kept on the device (`android-sdk`): the client keeps the last values it received from
  ConfigDirector in the app's `SharedPreferences`, under a key derived from the client SDK key, and
  serves them on the next launch of the same context, the same context kind and id, until live
  values arrive. A value served this way reports the new `EvaluationReason.CACHED` with
  `isDefaultValue` false; a key the kept values do not carry reports `CLIENT_NOT_READY` as before.
  The client is not ready while it serves them: `isReady` stays false, `isInitializing` stays true,
  `initialize` still waits for live values or its timeout, and `ClientEvent.Ready` fires once live
  values arrive. The new `ClientEvent.CachedValuesLoaded` fires when the kept values are loaded,
  followed by `ClientEvent.ConfigsUpdated` listing their keys; the first live full payload lists the
  kept keys it no longer carries in `removedKeys`. A launch with another context, a first launch and
  offline mode serve in-code default values as before, and a test client (`android-sdk-testing`)
  keeps nothing.
- `ClientOptions.caching` (`android-sdk`), a `CachingOptions` built with `CachingOptions.builder()`
  or `CachingOptions.build { }`: `enabled`, true by default, turns the kept values off; `provider`
  replaces the `SharedPreferences` store with a `CachingProvider` of your own, an interface of
  `suspend fun read(): String?`, `suspend fun write(value: String)`, `suspend fun remove()` and
  `fun sizeLimit(): Int` in bytes, implemented from Kotlin. The default store's limit is 256 KB; an
  entry above the store's limit is removed with one warning, a store that fails is logged once and
  never stops the client, and the entry's format belongs to the SDK and may change between versions.
- Offline mode (`android-sdk`): `ConnectionMode.OFFLINE` runs the client without connecting to
  ConfigDirector. It serves the values given with `ConnectionOptions.Builder.values`, one per
  config key and the same to every context, or every in-code default value with
  `CONFIG_STATE_MISSING` when no values are given. Each value's type decides its config's type: a
  `Boolean` is a boolean config, an `Int` or a `Long` an integer config, a `Float` or a `Double` a
  float config, a `String` a string config, and a `Map` with `String` keys or a `List` a JSON
  config. It sends
  nothing, so the app is invisible to the dashboard. `initialize` returns with the client ready and
  delivers `ClientEvent.Ready` once, `updateContext` takes the new context at once and changes no
  value, and `pauseNetwork` and `resumeNetwork` do nothing. `ConnectionOptions.values` reads the
  values back.
- `ConfigDirectorClient(androidContext, options)` (`android-sdk`) creates a client without a client
  SDK key, which only offline mode allows. The constructor that takes a key also accepts a blank
  one in offline mode.
- `ConnectionOptions.Builder.build()` (`android-sdk`) throws `ConfigDirectorValidationException` for
  values given in `STREAMING` or `POLLING` mode, and for a value that is null, not finite or of a
  type no config has, or that has a blank key. In offline mode it skips its timeout and base URL
  checks, since that mode uses neither.
- The Compose bindings (`android-sdk-compose`) work over an offline client with no change:
  `ConfigDirectorProvider(client)` takes one like any other client.

### Changed

- `EvaluationReason` (`android-sdk`) has a ninth entry and `ClientEvent` a fourth subclass, so a
  Kotlin `when` that lists every reason needs a `CACHED` branch and one that lists every event a
  `CachedValuesLoaded` branch.
- `ConnectionMode` (`android-sdk`) has a third entry, so a Kotlin `when` that lists every mode needs
  an `OFFLINE` branch. From Java, `new ConfigDirectorClient(context, null)` no longer compiles,
  because `null` matches both the key and the options constructor; it threw before.
- Instance ID (`android-sdk`): the instance ID the SDK stores in the app's `SharedPreferences` now
  lasts for the life of the install, instead of being replaced six hours after it was created, so a
  person using the app without a context ID counts as one monthly active user a month. An instance
  ID stored by an earlier version is kept.

### Fixed

- A test client (`android-sdk-testing`) given a null value from Java, in `createTestClient` or
  `replaceValues`, throws `ConfigDirectorValidationException`, as documented, instead of a
  `NullPointerException`.

## [1.8.0] - 2026-10-15

### Added

- A context can now carry a context kind (`android-sdk`), such as `device` or `organization`, set
  with `ConfigDirectorContext.Builder.kind`, or `kind(...)` in the Kotlin DSL, and read back as
  `ConfigDirectorContext.kind`. Leaving it out, or passing null, means `user`, as before, and
  `kind` then reads null. A context kind has 1 to 64 characters, using only lowercase letters,
  digits, `-` and `_`, and is sent exactly as given. `equals`, `hashCode` and `toString` include
  it, so a context without a context kind and one with context kind `user` are not equal, though
  both are evaluated as `user`.
- `initialize` and `updateContext` ignore a context whose context kind is invalid
  (`android-sdk`). They never throw: they log an error each time, stop using the previous context,
  and evaluate configs as if no context was given, so `client.context`,
  `ClientEvent.ContextUpdated`, `ConfigEvaluation.context` and Compose's `configContext()`
  (`android-sdk-compose`) read null afterwards. `build()` never throws because of the context kind.
  Telemetry counts each ignored context with its context kind, never its id, name or traits.
- The test client (`android-sdk-testing`) records an empty context in `contextUpdates` for an
  `initialize` or `updateContext` whose context has an invalid context kind, since the client
  connects without it.

### Changed

- The client (`android-sdk`) now keeps its instance ID in the app's private `SharedPreferences`
  and reuses it for six hours from when it was generated, so an app reopened within six hours
  continues the same client session instead of starting a new one. Before, every app start
  counted as a new client session. The ID is stored per app install, needs no permission, and is
  gone with an uninstall or a clear of the app's data. When the ID could not be read or stored,
  the client tells the server that the ID is ephemeral and that app start counts as a new client
  session, as before.

## [1.7.1] - 2026-10-04

### Fixed

- The Compose bindings (`android-sdk-compose`) now resolve in an app that does not import a Compose
  BOM. Their published Gradle metadata named `androidx.compose.runtime:runtime` without a version,
  so such an app failed with `Could not find androidx.compose.runtime:runtime:.`; they now depend
  on 1.12.0, the same version their own Compose BOM brought in before.
- The Compose bindings (`android-sdk-compose`) no longer apply their own Compose BOM to an app's
  other Compose libraries. An app on an older BOM compiled against its own versions of libraries
  such as `androidx.compose.ui:ui` but ran with the newer ones from the bindings' BOM; it now gets
  the same version of each at compile time and at runtime.

## [1.7.0] - 2026-10-04

### Changed

- The artifacts are now published as `com.configdirector:android-sdk`,
  `com.configdirector:android-sdk-compose`, and `com.configdirector:android-sdk-testing` from
  `https://maven.configdirector.com`, instead of as `com.configdirector:configdirector-android`,
  `com.configdirector:configdirector-android-compose`, and
  `com.configdirector:configdirector-android-testing` on Maven Central, where their last version is
  1.6.0. Upgrading means adding the repository to the build and changing the artifactIds; the README
  shows both. Move every artifact an app declares at once: an old and a new artifact hold the same
  classes and cannot be on the same classpath.

## [1.6.0] - 2026-10-01

### Added

- `com.configdirector:configdirector-android-testing`, a new artifact for testing the code that
  reads configs: `createTestClient(values, timeoutMillis, logger)` returns a `TestClient` whose
  `client` is the SDK's real `ConfigDirectorClient` over an in-memory connection the test controls
  through `setValue`, `removeValue`, `replaceValues`, `holdInitialization`,
  `completeInitialization`, `failInitialization`, `holdContextUpdate`, `completeContextUpdate`,
  `failContextUpdate`, and `contextUpdates`. It needs no Android `Context`, opens no connection,
  sends no telemetry, and runs in a plain JVM unit test, under Robolectric, or on a device. Java
  calls it as `ConfigDirectorTesting.createTestClient(values)`. The client logs to
  `StandardErrorLogger` unless given a logger. The artifact must be the same version as the SDK,
  which `createTestClient` checks.
- `com.configdirector.internal.testing.InMemoryConnection` and the `@ConfigDirectorTestingApi`
  opt-in marker (`configdirector-android`): the entry point `configdirector-android-testing`
  builds on. It runs the SDK's real client over an
  in-memory connection that a test controls, with no Android `Context`, network, or telemetry.
  Kotlin code that has not opted in cannot reach it; tests use the testing artifact instead.

- `ClientEvent.ConfigsUpdated.removedKeys` (`configdirector-android`): the keys a full update no
  longer carried, so a listener can tell a config that was removed from one that was updated.
  `keys` still lists only the keys the update carried.

### Fixed

- A watch on a config that a full update no longer carries is now handed its default value, as a
  getter would now return it (`configdirector-android`). Before, the config silently stopped being
  served and the watch kept its last value.

### Changed

- `isInitializing` (`configdirector-android`) now means the client is trying to get its very first
  config state. It becomes `true` when `initialize` is called on a client that has never received
  config state, stays `true` through timeouts and retries, and becomes `false` when the first
  config state arrives, when an unrecoverable connection error stops the retries, or on `close`.
  Before, it turned `false` as soon as `initialize` returned, including after a timeout while the
  client kept retrying, and a later `initialize` set it again. `updateContext` and `resumeNetwork`
  never set it.
- `ConnectionOptions.pollingIntervalMillis` now defaults to 60 seconds instead of 5 minutes, and
  its minimum is 30 seconds instead of 60. An interval below the minimum is no longer rejected when
  the options are built: the options keep the configured value, and a client created in `POLLING`
  mode raises it to the minimum and logs one warning.
- Whichever of the app name and version `Metadata` leaves unset is now read from the application:
  the name from its label and the version from its `versionName`, as the Swift and Flutter SDKs
  already do. Setting a field still sends that value instead. When one cannot be read, the client
  says so at `INFO` when it is created.

## [1.5.1] - 2026-09-26

### Fixed

- The telemetry report now carries the app name and version from `Metadata`, as the config
  requests already did, so evaluations can be shown per app in the dashboard's activity graphs.

## [1.5.0] - 2026-09-25

### Changed

- A boolean, integer, or float config read with `getString` or `evaluateString` now evaluates to
  the default value with the `TYPE_MISMATCH` reason, instead of the value's text with
  `FOUND_MATCH`. Reading a JSON config as a string still returns its raw document.

## [1.4.0] - 2026-09-21

### Added

- `SdkIdentity`, and a `ConfigDirectorClient` constructor taking one, through which a wrapper
  maintained by ConfigDirector reports its own name and version to the server in place of the
  SDK's. The set of identities is closed: there is a factory per wrapper, and no way to build one
  from an arbitrary name. Both are behind the `@ConfigDirectorWrapperApi` opt-in marker, since an
  application has no use for them. This is groundwork for the OpenFeature provider.
- An evaluation per readable type, `evaluateBoolean` through `evaluateJsonArray`, returning the
  `ConfigEvaluation` the getter of the same type would have published: the value, whether it is
  the default, the reason, and the value id. For Kotlin, `evaluate` reads the type from the
  default value. Use it where the reason matters at the point of the read; the getters are enough
  everywhere else.

## [1.3.0] - 2026-09-18

### Fixed

- HTTP 429 response codes from the SDK server are no longer treated as a fatal error. These need to be handled as transient errors so the client continues to retry and reconnects once the rate limit is cleared.

## [1.2.0] - 2026-09-05

### Changed

- The polling interval now defaults to 5 minutes instead of 60 seconds, and an interval shorter
  than 60 seconds is rejected when the options are built.

### Removed

- `ConnectionMode.ONE_TIME`. Use `STREAMING`, or `POLLING` with an interval of at least 60
  seconds; both fetch config state during initialization and on context updates.

## [1.1.0] - 2026-09-01

### Changed

- The artifacts moved from the `com.configdirector.android` group to `com.configdirector`, which is
  where the other ConfigDirector SDKs live. Nothing else changed: update the group in your
  dependency declaration and carry on.

  ```kotlin
  implementation("com.configdirector:configdirector-android:1.1.0")
  implementation("com.configdirector:configdirector-android-compose:1.1.0")
  ```

  1.0.0 stays on Maven Central under the old group, since a published version cannot be withdrawn.
  It receives no further versions, and this is the release to move to.

## [1.0.0] - 2026-09-01

Published as `com.configdirector.android:configdirector-android`. Use 1.1.0 above instead.

### Added

- `ConfigDirectorClient`, built from an Android context and a client SDK key, evaluating configs
  against a `ConfigDirectorContext` of `id`, `name`, `traits` and `isAnonymous`. Building one makes
  no network calls; `initialize` connects and waits for the first config state. `updateContext`
  re-evaluates every config against a new identity, keeping the previous one in effect until the
  reconnection succeeds or times out.
- Typed getters — `getBoolean`, `getString`, `getInt`, `getDouble`, `getJsonObject` and
  `getJsonArray`. A getter returns the default it was given rather than throwing, whether the
  config is unknown, the server unreachable, or the value will not read as that type. A JSON
  document is parsed once and handed back unmodifiable.
- A watch per type, mirroring the getters: `watchBoolean` through `watchJsonArray`. Each delivers
  the config's current value straight away and then every value it changes to, on the main thread,
  without re-delivering a value that did not change, and returns a `Subscription` that stops it.
- `addEventListener` and `addEvaluationListener`, publishing what the client does and every
  individual evaluation, each with the `EvaluationReason` saying why a value was served.
- Kotlin conveniences over that API, in a source set Java never sees: `value` and `values` reading
  a config as the type of its default, and `events` and `evaluations` as flows. `initialize` and
  `updateContext` are `suspend` for Kotlin and take a `CompletionCallback` for Java.
- Three connection modes through `ConnectionOptions`: `STREAMING` over server-sent events,
  `POLLING` on an interval, and `ONE_TIME`. Streaming reconnects on its own with a backoff capped
  just under ten minutes, and stops on a status that retrying cannot fix.
- `pausesWhileBackgrounded`, dropping the connection while the app is backgrounded and restoring it
  on the way back, plus `pauseNetwork()` and `resumeNetwork()` to drive that by hand. Backgrounding
  is detected from `Application.ActivityLifecycleCallbacks`, and a configuration change is not
  mistaken for the app leaving.
- Telemetry that aggregates config evaluations and reports them off the caller's thread, so reading
  a config never waits on the network and never spells out a value on the reading thread.
- `ConfigDirectorLogger`, with an `AndroidLogger` writing to logcat under the `ConfigDirector` tag,
  and a `LogLevel` that leaves a dropped message unbuilt.
- Builders for `ClientOptions`, `ConnectionOptions` and `ConfigDirectorContext`, each with a Kotlin
  DSL over the same type. Unusable settings are rejected where they are written rather than
  surfacing later as a client that quietly never updates.
- `configdirector-android-compose`, an optional artifact holding Compose bindings over the same
  client and no logic of its own: `ConfigDirectorProvider`, `configValue` with an overload per
  readable type, `isClientReady` and `configContext`. It depends on `androidx.compose.runtime`
  alone.
- Support for Android 5.0 (API 21) and up, in Java 8 bytecode, with the core depending on the
  Kotlin standard library, coroutines and OkHttp. The `INTERNET` permission is declared by the SDK
  and merged into the consuming application. The public API is annotated for nullability throughout
  and shaped so that every part of it is callable from Java.
