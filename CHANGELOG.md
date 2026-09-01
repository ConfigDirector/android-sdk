# Changelog

Changes to `com.configdirector.android:configdirector-android` and
`com.configdirector.android:configdirector-android-compose`. The two are released together and
share a version, so they share this file; an entry says which artifact it belongs to when it is not
both.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and these artifacts
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-09-01

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
