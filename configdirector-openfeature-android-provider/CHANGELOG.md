# Changelog

Changes to `com.configdirector:openfeature-android-provider`. It is released on its own, so it has
a version and a changelog of its own; the SDK it wraps has [its own changelog](../CHANGELOG.md).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this artifact
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.3.0] - 2026-10-04

### Changed

- The artifact is now published as `com.configdirector:openfeature-android-provider` from
  `https://maven.configdirector.com`, instead of as
  `com.configdirector:configdirector-openfeature-android-provider` on Maven Central, where its last
  version is 1.2.0. Upgrading means adding the repository to the build and changing the artifactId;
  the README shows both. The provider now depends on the SDK under its new name,
  `com.configdirector:android-sdk` 1.7.0, so an app that also declares the SDK or its testing tools
  moves them to their new names at the same time.

## [1.2.0] - 2026-10-01

### Added

- A constructor taking a `ConfigDirectorClient`, behind the `@ConfigDirectorProviderTestingApi`
  opt-in, so a test creates the provider over the `client` of a test client from
  `com.configdirector:configdirector-android-testing` and drives the flags the OpenFeature client
  resolves. The provider never closes a client it was given. Requires version 1.6.0 of the SDK,
  which ships the testing tools.

### Changed

- The configuration-changed event now lists the flags a full update removed in `flagsChanged`,
  after the flags the update carried. Before, a removed flag was not reported as changed. Requires
  version 1.6.0 of the SDK, which adds `ClientEvent.ConfigsUpdated.removedKeys`.

## [1.1.1] - 2026-09-26

- Bump dependency on client SDK with telemetry fix

## [1.1.0] - 2026-09-25

- Bump dependency on client SDK with fix for evaluation type mismatches

## [1.0.0] - 2026-09-21

### Added

- `ConfigDirectorProvider`, an [OpenFeature](https://openfeature.dev) provider for the OpenFeature
  Kotlin SDK 0.8, built from an Android context, a client SDK key and the SDK's `ClientOptions`.
  It connects when registered, evaluates every flag from the config state it holds locally, and
  identifies itself to the server as the OpenFeature provider rather than as the SDK.
- The OpenFeature evaluation context is sent as the user's context: the targeting key, or else
  `id`, as the id, `name` as the name, the `traits` structure as the traits, and `anonymous` as
  the anonymous flag.
- Resolution details: a served flag carries the reason `TARGETING_MATCH` and the served value's id
  as its variant; a config with no value for the context resolves to the default with the reason
  `DEFAULT`; an unknown key, a not-yet-ready provider and a value that cannot be read as the
  requested type resolve to the default with `FLAG_NOT_FOUND`, `PROVIDER_NOT_READY` and
  `TYPE_MISMATCH`. An object flag takes its type from its default value.
- Provider events: a configuration-changed event carrying the keys of every update, and a ready
  event when the connection succeeds after initialization failed. Initialization and a context
  change fail with `ProviderNotReadyError` when ConfigDirector does not deliver config state
  within the connection timeout, and with `InvalidContextError` for a context that cannot be sent.
- Support for Android 5.0 (API 21) and up, in Java 11 bytecode.
