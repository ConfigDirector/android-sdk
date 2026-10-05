# Changelog

Changes to `com.configdirector:android-sdk`, `com.configdirector:android-sdk-compose`, and
`com.configdirector:android-sdk-testing`. The three are released together and share a version, so
they share this file; an entry says which artifact it belongs to when it is not all of them.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and these artifacts
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
