# Contributing

## What you need

- **A JDK 17 or newer.** CI builds on 21 and 25 — 21 because that is what Android Studio ships,
  25 because it is current. The Android Gradle plugin is fussier about the JDK running Gradle than
  about anything else in this build.
- **A JDK 21 as well, if you can.** The two versions disagree about more than they look like they
  should: a javac lint category that exists in one and not the other is an error rather than a
  warning, so a build that is green on 25 can fail on 21. The pre-push hook compiles on 21 when it
  finds one, and says it skipped when it does not.

  ```sh
  sdk install java 21.0.12+1.1-tem   # or set JAVA21_HOME to one you already have
  ```
- **The Android SDK.** Opening the project in Android Studio writes `local.properties` for you;
  otherwise write it yourself, or export `ANDROID_HOME`:

  ```sh
  echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
  ```

  `local.properties` is machine-specific and git-ignored. Gradle downloads the SDK platform and
  build tools the build asks for.

Nothing else. The build brings its own Gradle through the wrapper.

## The four artifacts

Each module publishes under an artifactId of its own, named with `coordinates(...)` in its build
script; the module directories keep the older names the artifacts were first published under.

`configdirector-android`, published as `com.configdirector:android-sdk`, is the whole SDK: Kotlin,
no Compose, consumable from Java, `minSdk 21` and Java 8 bytecode.

`configdirector-android-compose`, published as `com.configdirector:android-sdk-compose`, adds
Compose bindings over it and nothing else — no client logic of its own. It depends on
`androidx.compose.runtime` alone, deliberately: bindings that pulled in `compose-ui` or `material3`
would put those versions in every consumer's dependency graph. It keeps `minSdk 21` as well; only
Java 11 bytecode differs, because that is what AndroidX ships.

Compose is a Kotlin compiler plugin, so the Compose artifact has no Java source set and the Java
test rule below does not apply to it.

`configdirector-android-testing`, published as `com.configdirector:android-sdk-testing`, is the
testing tools: the SDK's real client over an in-memory connection that a test controls, wrapped in
the test client API a consumer's tests use. It is the only consumer of the SDK's
`@ConfigDirectorTestingApi` entry point, which is not part of the SDK's stable API, so it is
released with the SDK, shares its version, pins that version in its published metadata, and refuses
any other at runtime. Java 8 bytecode and `minSdk 21`, like the core, and consumable from Java, so
the Java test rule applies to it. It depends on `org.json:json`, because the framework's `org.json`
is a stub under a plain JVM unit test and JSON configs would not parse without a real one; on a
device the framework's own wins, because the boot class loader is consulted first, so an
instrumented test is unaffected. Android lint's `DuplicatePlatformClasses` check objects to that
dependency all the same, so the module disables that one check, and only that one; a consumer's lint
does not see the dependency, because it is transitive.

`configdirector-openfeature-android-provider`, published as
`com.configdirector:openfeature-android-provider`, is an [OpenFeature](https://openfeature.dev)
provider over the core, for the OpenFeature Kotlin SDK. That SDK's provider contract is built on
`suspend` functions and flows, so the provider is Kotlin-only and the Java test rule does not apply
to it either. It ships Java 11 bytecode, because the OpenFeature Kotlin SDK does, and keeps
`minSdk 21`. It is versioned and released apart from the other three; see [Releasing](#releasing).
Its constructor that takes a client, behind `@ConfigDirectorProviderTestingApi`, is tested over the
testing tools' test client in plain JVM tests, which is how a consumer's test uses it.

The core, the Compose bindings, and the provider use Robolectric in their tests, and only there. The
Compose bindings need a composition to run in; the core and the provider need a real `Application`,
because the client takes a `Context` and watches the application behind it to tell when the app is
backgrounded. Tests that never build a client — the options, the context, the parsers, the telemetry
queue, the provider's mappings — stay plain JVM tests. So do the testing tools' own tests, on
purpose: a consumer's plain JVM test is the first place the test client has to work, and that suite
is what proves it needs nothing from Android. The Compose bindings' tests cover a composable over a
test client, which is what a consumer's Compose test looks like.

The samples are tested the way a consumer tests an app: through the testing tools, as a
`testImplementation` dependency on the released `com.configdirector:android-sdk-testing`, which
`-PuseLocalSdk` swaps for the module here like the artifact each sample demonstrates. Both Compose
samples drive their screen under Robolectric with `createComposeRule`, one over
`ConfigDirectorProvider(testClient.client)` and the other over the provider registered with a test
client; the Java sample starts its `Activity` under Robolectric from an `Application` subclass that
returns the test client where the production one builds a client. Each sample's README describes
its tests. Without the flag the testing coordinate resolves only once the release that ships it is
in the ConfigDirector Maven repository.

## Building and testing

```sh
./gradlew build
```

That is the whole check. It compiles all four artifacts, the two test source sets of the core and
of the testing tools, and every sample app; runs the unit tests; and runs Android lint, whose failures fail the build — lint is
what catches an API that needs a newer Android than the SDK's `minSdk 21`.

The samples resolve the SDK from the ConfigDirector Maven repository, which `settings.gradle.kts`
declares, the way a consumer does. Build them against the working tree instead with:

```sh
./gradlew build -PuseLocalSdk
```

That is what CI and the pre-push hook run, so a breaking API change fails in a real consumer before
it ships.

Narrower loops while working:

```sh
./gradlew :configdirector-android:testDebugUnitTest                      # the core's tests alone
./gradlew :configdirector-android-compose:testDebugUnitTest              # the Compose bindings' tests
./gradlew :configdirector-openfeature-android-provider:testDebugUnitTest # the provider's tests
./gradlew :configdirector-android:assembleDebug                          # the AAR alone
./gradlew :samples:configdirector-android:compose:testDebugUnitTest -PuseLocalSdk  # one sample's tests
```

Running the sample apps is covered in [the SDK samples' README](samples/configdirector-android/README.md)
and [the provider sample's](samples/configdirector-openfeature-android-provider/README.md).

## Dependencies

The SDK depends on the Kotlin stdlib, coroutines, and OkHttp. Every addition to that list is a
potential version conflict in a consumer's build, so the bar for a new one is high.

**OkHttp 4.x, not 5.x.** OkHttp 5 is a multiplatform publication whose Android variant,
`okhttp-android`, declares `minCompileSdk 37`. That is not an extra dependency to exclude — it *is*
OkHttp on Android — and the floor passes through this AAR to every consumer, so an app on
`compileSdk 34` could not use the SDK at all. It would also drag an app pinned to OkHttp 4 up to a
new major. 4.12.0 is a plain jar with no such floor, and it is what most Android apps already have.

**`minCompileSdk` is declared, not inherited.** Left alone, the AAR's floor follows this module's
`compileSdk`, which would make every consumer move to the newest SDK to take an SDK update. The SDK
touches almost nothing of the Android framework, so `aarMetadata.minCompileSdk` says 21 and the
build compiles against the newest SDK regardless.

Server-sent events come from `okhttp-sse` rather than a parser of our own. It handles the framing;
the reconnection policy is ours, because OkHttp deliberately has none. What it does not surface —
stream comments, and the server's `retry:` field — the reference SDKs ignore too: their transports
back off on their own schedule.

## Every public API needs a Java test

The SDK is Kotlin and serves two kinds of consumer: modern Kotlin apps, and Java codebases that
predate coroutines and Compose. A Kotlin test cannot tell whether an API is usable from the second
kind — a `suspend` function, a `Flow`, an inline reified accessor or an `internal` member all
compile for a Kotlin caller and are unreachable, ambiguous or ugly from Java. An interop regression
is invisible until a customer hits it.

So every public API addition is exercised from `src/test/java` as well as `src/test/kotlin`, and
`JavaSurfaceTest` guards the shape of what Java sees: no mangled `internal` names, no `Function1`
or `Continuation` parameters, nothing from the Kotlin-only extensions. The testing tools have a
`src/test/java` and a `JavaSurfaceTest` of their own, for the same reason.

A build can also go green with the Java tests silently not running at all, so
[`check-java-tests-ran.sh`](.github/scripts/check-java-tests-ran.sh) fails when it finds no Java
test results for the core, for the testing tools, or for the Java sample, whose tests are the
testing tools' Java surface in a real consumer. CI and the hook both run it after `build`.

## The published API is locked down

Each artifact's `api/<artifact>.api` is its public API, as Java and Kotlin consumers see it.
`apiCheck` regenerates the signatures from the
release AAR and fails when they differ from the committed file; `check` depends on it, so
`./gradlew build`, CI and the hook all catch an accidental break.

```sh
./gradlew apiDump   # after an intended API change; commit the result with it
./gradlew apiCheck  # what the build runs for you
```

A diff in these files is the API review. An entry disappearing or changing shape breaks every app
already compiled against it, so a pull request that changes one without saying why in its
description is the thing to push back on.

The signatures come from the same engine as the Kotlin binary compatibility validator. Its Gradle
plugin only wires itself up for the `kotlin`, `kotlin-android` and `kotlin-multiplatform` plugin
ids, and the Android Gradle plugin brings its own Kotlin instead, so
[`buildSrc`](buildSrc/src/main/kotlin/com/configdirector/gradle/ApiValidation.kt) feeds that engine
the release AAR directly. Reading the AAR rather than the compiled classes means the check sees
exactly what we publish.

## Wrappers identify themselves through a closed API

The SDK sends its name and version with every request, and a wrapper built on it, such as the
OpenFeature provider, has to send its own instead, so the server's SDK usage tells the two apart.
`SdkIdentity` is how: a wrapper builds its client with the constructor that takes one.

The set of identities is closed on purpose. There is a factory per wrapper ConfigDirector
maintains, taking only the version that wrapper is published under, and no constructor an
application could hand an arbitrary name to. Adding a wrapper means adding a factory here, not
opening the type up.

Both the type and the constructor sit behind `@ConfigDirectorWrapperApi`, a Kotlin opt-in marker. A
Kotlin caller that has not opted in gets a compile error rather than an API that looks meant for
them; a wrapper opts in with `@OptIn(ConfigDirectorWrapperApi::class)` where it builds its client.
Java does not enforce opt-in, which is fine: the closed set is what keeps the name honest, and the
marker is there to keep the API out of an application's way. Both still count as public API, so
they are in the API dump and exercised from the Java test source set like everything else.

## Tests have to be shown to work

A test that passes against a bug is worse than no test. Either write it before the code and watch
it fail for the right reason, or write it after and then break the implementation on purpose to
watch it fail. Test names say what the code does, not which method they call.

## Releasing

Releases go to the ConfigDirector Maven repository, `https://maven.configdirector.com`. The
repository is a Cloudflare R2 bucket; its setup, and a dev twin at
`https://maven.configdirector-dev.com`, are documented in the `config-director` repository under
`infrastructure/cloudflare/maven-repository/`. Each release workflow takes a `repository` input,
`dev` or `prod`, and runs in the matching GitHub environment, `maven-dev` or `maven-prod`, which
holds the bucket's credentials. `maven-prod` requires the product owner's approval before the job
starts, which is the last check before a version becomes permanent.

The core, the Compose bindings, and the testing tools share the one `VERSION_NAME` in
`gradle.properties` and are always released together, because the Compose bindings and the testing
tools depend on the core of the same version, and the testing tools refuse any other at runtime. The
OpenFeature provider is released on its own; see [below](#the-openfeature-provider).

1. **Prepare the release on `main`.** In one PR:
   - Move the `[Unreleased]` entries in `CHANGELOG.md` under a new `## [<version>] - <date>`
     heading.
   - Bump `VERSION_NAME` in `gradle.properties`.
   - Bump `Constants.SDK_VERSION` in
     [`Constants.kt`](configdirector-android/src/main/kotlin/com/configdirector/internal/Constants.kt)
     to the same version — it is what the SDK reports to the server, and `ConstantsTest` fails the
     build when the two disagree.
   - Bump `Constants.TESTING_VERSION` in
     [`Constants.kt`](configdirector-android-testing/src/main/kotlin/com/configdirector/testing/internal/Constants.kt)
     to the same version — it is what the testing tools check the SDK against, and their
     `ConstantsTest` fails the build when it disagrees with the Gradle version.
   - Bump the versions in the README install snippets.

   Merge it.

2. **Run the [Release configdirector-android](.github/workflows/release.yml) workflow against
   `main` with `repository` set to `dev`** (Actions tab → Release configdirector-android → Run
   workflow), and check the result against `https://maven.configdirector-dev.com`. The workflow
   releases whatever version `main` declares, for all three artifacts together. It first checks
   that none of them is in the bucket yet, then runs everything CI runs, checks that each
   publication carries the version being released, publishes the three signed artifacts into a
   staging repository under `build/maven-repository` in one Gradle invocation, attests every AAR,
   jar and POM, and uploads them. The check and the upload are the actions in
   [maven-repository-actions](https://github.com/ConfigDirector/maven-repository-actions), which
   also documents what the upload guarantees.

3. **Run it again with `repository` set to `prod`**, and approve the `maven-prod` environment when
   GitHub asks. Only a `prod` run tags the commit, `v<version>`, and it does so last.

4. **Once the version resolves from `https://maven.configdirector.com`, bump both samples to it**
   in a follow-up PR: the `implementation` line and the `testImplementation` line naming
   `com.configdirector:android-sdk-testing`, which must stay the same version as the SDK. The
   samples deliberately lag the SDK: naming a version that is not published yet leaves them
   unresolvable for anyone not passing `-PuseLocalSdk`. Then run their tests without the flag,
   since CI only ever runs them with it.

A version whose POM is already in the bucket is refused before anything is built or uploaded: a
released version is never replaced, so a change after a release needs a new version. A run that
failed before the POMs went up can simply be run again.

### The OpenFeature provider

`com.configdirector:openfeature-android-provider` follows the same steps with its own version,
`OPENFEATURE_PROVIDER_VERSION_NAME` in `gradle.properties`, its own
[changelog](configdirector-openfeature-android-provider/CHANGELOG.md), its own version constant in
[`Constants.kt`](configdirector-openfeature-android-provider/src/main/kotlin/com/configdirector/openfeature/internal/Constants.kt),
which its `ConstantsTest` holds to the Gradle version, and the
[Release configdirector-openfeature-android-provider](.github/workflows/release-openfeature-provider.yml)
workflow, which tags a `prod` release `configdirector-openfeature-android-provider-v<version>`.

Its published POM depends on whatever `VERSION_NAME` the same commit declares, so that SDK version
must already be in the repository the provider is released to. Once the provider resolves from
`https://maven.configdirector.com`, bump the sample under
`samples/configdirector-openfeature-android-provider/` to it.

It has a version of its own because it follows two things: the SDK it wraps, and the OpenFeature
Kotlin SDK, which is 0.x and can break on a minor. A bump for either should not force a release of
the other.

The publish plugin reads `VERSION_NAME` from `gradle.properties` on its own and lets it override a
module's `version`, which is how the provider once went out under the SDK's version. Its build
script names its coordinates explicitly to stop that, and both release workflows generate each
publication's POM and refuse to upload unless the version in it is the one being released.

### Signing

Every published file is signed with the key in [KEYS.md](KEYS.md). The release workflows read it
from the `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` repository secrets, an ASCII-armoured GPG secret
key and its passphrase. Signing is skipped when no key is configured, so a local
`./gradlew publishToMavenLocal` works without one — useful for trying a change against a real
consuming app before it is released.

## The pre-push hook

Install it once:

```sh
git config core.hooksPath .githooks
```

It runs the same checks CI does, against your working tree rather than the commits being pushed:
`./gradlew build -PuseLocalSdk`, the Java test check, and — when a JDK 21 is installed — a compile
pass on 21. Bypass it for a single push with `git push --no-verify`.

## What CI runs

[`configdirector-android.yml`](.github/workflows/configdirector-android.yml) runs `./gradlew build`
and the Java test check on JDK 21 and 25, with `useLocalSdk` set for the whole workflow, and keeps
the AAR and the sample APKs as artifacts. Test and lint reports are uploaded when a job fails.

[`release.yml`](.github/workflows/release.yml) and
[`release-openfeature-provider.yml`](.github/workflows/release-openfeature-provider.yml) are the
manual releases described above. They are the only workflows that write to the Maven repository,
and the only ones that need secrets.
