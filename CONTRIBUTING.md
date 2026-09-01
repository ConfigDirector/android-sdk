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

## The two artifacts

`configdirector-android` is the whole SDK: Kotlin, no Compose, consumable from Java, `minSdk 21` and
Java 8 bytecode.

`configdirector-android-compose` adds Compose bindings over it and nothing else — no client logic of
its own. It depends on `androidx.compose.runtime` alone, deliberately: bindings that pulled in
`compose-ui` or `material3` would put those versions in every consumer's dependency graph. It keeps
`minSdk 21` as well; only Java 11 bytecode differs, because that is what AndroidX ships.

Compose is a Kotlin compiler plugin, so the Compose artifact has no Java source set and the Java
test rule below does not apply to it.

Both artifacts use Robolectric in their tests, and only there. The Compose bindings need a
composition to run in; the core needs a real `Application`, because the client takes a `Context` and
watches the application behind it to tell when the app is backgrounded. Tests that never build a
client — the options, the context, the parsers, the telemetry queue — stay plain JVM tests.

## Building and testing

```sh
./gradlew build
```

That is the whole check. It compiles both artifacts, the core's two test source sets, and both
sample apps; runs the unit tests; and runs Android lint, whose failures fail the build — lint is
what catches an API that needs a newer Android than the SDK's `minSdk 21`.

The samples resolve the SDK from Maven Central, the way a consumer does. Build them against the
working tree instead with:

```sh
./gradlew build -PuseLocalSdk
```

That is what CI and the pre-push hook run, so a breaking API change fails in a real consumer before
it ships.

Narrower loops while working:

```sh
./gradlew :configdirector-android:testDebugUnitTest          # the core's tests alone
./gradlew :configdirector-android-compose:testDebugUnitTest  # the Compose bindings' tests
./gradlew :configdirector-android:assembleDebug              # the AAR alone
```

Running the sample apps is covered in [their README](samples/configdirector-android/README.md).

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
or `Continuation` parameters, nothing from the Kotlin-only extensions.

A build can also go green with the Java tests silently not running at all, so
[`check-java-tests-ran.sh`](.github/scripts/check-java-tests-ran.sh) fails when it finds no Java
test results. CI and the hook both run it after `build`.

## The published API is locked down

`api/configdirector-android.api` and `api/configdirector-android-compose.api` are the public API of
each artifact, as Java and Kotlin consumers see it. `apiCheck` regenerates the signatures from the
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

## Tests have to be shown to work

A test that passes against a bug is worse than no test. Either write it before the code and watch
it fail for the right reason, or write it after and then break the implementation on purpose to
watch it fail. Test names say what the code does, not which method they call.

## Releasing

Both artifacts share one version, in `gradle.properties`:

```properties
GROUP=com.configdirector
VERSION_NAME=1.1.0
```

They are released together, because the Compose bindings depend on the core of the same version.

A release is two steps: merge the `VERSION_NAME` bump, then run the **Release configdirector-android**
workflow against `main`. It builds and tests everything CI does, uploads a signed bundle per
artifact to the Maven Central Portal, and tags the commit `v<version>`. It refuses to run when that
tag already exists, so a version cannot be released twice by accident.

The artifacts are uploaded one Gradle invocation each, so that the Portal names each deployment
after the artifact it carries — `com.configdirector-configdirector-android-<version>` —
rather than after the group. A build that publishes both at once is named after the group and the
version instead, which says nothing about which artifact it holds.

A bundle is not a published version: each deployment waits in the
[Central Portal](https://central.sonatype.com) until someone releases it by hand, which is the last
look at what is about to become permanent. **Both have to be released** for the version to be
usable, since the Compose bindings depend on the core of the same version. Dropping them there
instead means deleting the tag before running the workflow again.

Once the version resolves on Central, bump both samples to it. They deliberately lag the SDK:
naming a version that is not published yet leaves them unresolvable for anyone who is not passing
`-PuseLocalSdk`.

The workflow needs four repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`
(a Portal user token, not the account password), and `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` (an
ASCII-armoured GPG secret key and its passphrase). Signing is skipped when no key is configured, so
a local `./gradlew publishToMavenLocal` works without one — useful for trying a change against a
real consuming app before it is released.

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

[`release.yml`](.github/workflows/release.yml) is the manual release described above. It is the only
workflow that touches Maven Central, and the only one that needs secrets.
