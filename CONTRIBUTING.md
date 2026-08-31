# Contributing

## What you need

- **A JDK 17 or newer.** CI builds on 21 and 25 — 21 because that is what Android Studio ships,
  25 because it is current. The Android Gradle plugin is fussier about the JDK running Gradle than
  about anything else in this build.
- **The Android SDK.** Opening the project in Android Studio writes `local.properties` for you;
  otherwise write it yourself, or export `ANDROID_HOME`:

  ```sh
  echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
  ```

  `local.properties` is machine-specific and git-ignored. Gradle downloads the SDK platform and
  build tools the build asks for.

Nothing else. The build brings its own Gradle through the wrapper.

## Building and testing

```sh
./gradlew build
```

That is the whole check, and it is what CI and the pre-push hook run. It compiles the SDK, both of
its test source sets, and both sample apps; runs the unit tests; and runs Android lint, whose
failures fail the build — lint is what catches an API that needs a newer Android than the SDK's
`minSdk 21`.

Narrower loops while working:

```sh
./gradlew :configdirector-android:testDebugUnitTest      # the tests alone
./gradlew :configdirector-android:assembleDebug          # the AAR alone
```

Running the sample apps is covered in [their README](samples/configdirector-android/README.md).

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

## Tests have to be shown to work

A test that passes against a bug is worse than no test. Either write it before the code and watch
it fail for the right reason, or write it after and then break the implementation on purpose to
watch it fail. Test names say what the code does, not which method they call.

## The pre-push hook

Install it once:

```sh
git config core.hooksPath .githooks
```

It runs the same two checks CI does, against your working tree rather than the commits being
pushed. Bypass it for a single push with `git push --no-verify`.

## What CI runs

[`configdirector-android.yml`](.github/workflows/configdirector-android.yml) runs `./gradlew build`
and the Java test check on JDK 21 and 25, and keeps the AAR and the sample APKs as artifacts. Test
and lint reports are uploaded when a job fails.
