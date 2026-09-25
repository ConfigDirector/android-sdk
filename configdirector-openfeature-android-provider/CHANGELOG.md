# Changelog

Changes to `com.configdirector:configdirector-openfeature-android-provider`. It is released on its
own, so it has a version and a changelog of its own; the SDK it wraps has
[its own changelog](../CHANGELOG.md).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this artifact
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
