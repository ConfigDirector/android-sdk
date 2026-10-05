# Java sample

A plain Java app reading the same configs as [the Compose sample](../compose), through the API a
Java caller actually gets. See [the sample overview](../README.md) for what the configs are, what
the three identities carry, and how to run this.

## Why it is written this way

The SDK is Kotlin, and its tests are mostly Kotlin. A Kotlin test cannot tell whether an API is
usable from Java: a `suspend` function, a `Flow`, an inline reified accessor and an `internal`
member all compile for a Kotlin caller and are unreachable, ambiguous or ugly here. An interop
regression is invisible until a customer hits it.

So this module is Java on the SDK's own floor, and nothing else:

- **no Kotlin sources** — the Kotlin stdlib arrives only as a transitive dependency of the SDK
- **no AndroidX, no Compose** — `android.app.Activity` and framework widgets, the way an app that
  predates both is written
- **minSdk 21 and Java 8 bytecode**, matching the SDK, so an API needing a newer Android or a newer
  Java fails here
- **`-Xlint:all -Werror`**, so a deprecation or an unchecked call in SDK-facing code fails the build

If the Java surface breaks, this module stops compiling. That is the whole job.

## What Java gets instead of suspend and Flow

| Kotlin                              | Java                                                       |
| ----------------------------------- | ---------------------------------------------------------- |
| `suspend fun initialize(context)`   | `initialize(context, callback)`, called back on the main thread |
| `suspend fun updateContext(context)`| `updateContext(context, callback)`                          |
| `client.values(key, default)`       | `client.watchBoolean(key, default, listener)` and friends |
| `client.events`                     | `client.addEventListener(listener)`                         |
| `client.evaluations`                | `client.addEvaluationListener(listener)`                    |

The listener registrations are the primitive; the Kotlin flows are built on top of them, not the
other way around. Each returns a `Subscription`, which is a `Closeable`, so a watch can be closed
directly or with try-with-resources.

Pass `null` as the context to `initialize` when there is nothing to evaluate against yet — Java has
no default arguments, so the parameter is always there.

## Callbacks arrive on the main thread

Every listener and every completion callback is handed back on the main thread, so
[`MainActivity`](src/main/java/com/configdirector/sample/java/MainActivity.java) sets text on views
straight from a watch:

```java
client.watchBoolean(
    "temporary-feature-flag", true, value -> onWatched("temporary-feature-flag", value));
```

The logger is the exception, and the one place the difference matters. The SDK writes logs from
whatever thread it is on, so [`SampleLogger`](src/main/java/com/configdirector/sample/java/SampleLogger.java)
hands them to a synchronized [`SampleLog`](src/main/java/com/configdirector/sample/java/SampleLog.java)
rather than touching a view.

## What the buttons do

**Configured / Beta tester / Anonymous** call `updateContext`. The client reconnects, re-evaluates every
config against the new identity, and the watches deliver whatever changed. A config whose value is
the same for both identities is not delivered again.

**Read every config** reads each config directly with `getBoolean`, `getString`, `getInt`,
`getDouble` and `getJsonObject`, including two of the ways an evaluation falls back to the default
it was given:

```
'no-such-config' fell back to fallback (config-state-missing, beta-tester)
'integer-config' fell back to true (type-mismatch, beta-tester)
```

`integer-config` holds an integer, so reading it as a boolean is a type mismatch rather than an
error — every accessor returns the default it was given instead of throwing. Reading it as a double
is not: a whole number is a number, so that one resolves.

**Every API** runs [`ApiTour`](src/main/java/com/configdirector/sample/java/ApiTour.java): every call
the screen itself has no reason to make, gathered in one place so the compiler covers it too — the
defaults, `ConfigDirectorContext.empty()`, the shortest constructor, pausing and resuming the
connection by hand, options built for polling through a proxy, and the two validation failures:

```
blank SDK key rejected: No client SDK key was provided. ...
zero timeout rejected: Invalid timeoutMillis '0'. It must be a positive number of milliseconds.
```

**Close client** closes the client while the app keeps running, which is what an app does on
sign-out. The client stops being ready and never reconnects; reads keep serving the last config
state it received. Nothing else closes it — see
[`SampleApplication`](src/main/java/com/configdirector/sample/java/SampleApplication.java).

The watches and listeners are closed in `onDestroy`, because they belong to the screen. The client
belongs to the application and outlives it.

## Tests

[`MainActivityTest`](src/test/java/com/configdirector/sample/java/MainActivityTest.java) starts the
screen under Robolectric with the client replaced by a **test client** from
`com.configdirector:android-sdk-testing`, which Java reaches as
`ConfigDirectorTesting.createTestClient(values)`: the SDK's real client over an in-memory connection
the test controls, so nothing connects and no key is needed.

The swap happens where the application builds its client. `SampleApplication.createClient()` is the
one method that knows about the SDK key, and
[`TestSampleApplication`](src/test/java/com/configdirector/sample/java/TestSampleApplication.java)
overrides it to return `testClient.getClient()`; the test's `@Config(application = ...)` has
Robolectric run that subclass, and everything else, `onCreate` included, is the production code.

The tests cover the watched values once the client is ready, a watch re-delivering after
`testClient.setValue`, the default coming back after `testClient.removeValue`, the identity buttons
calling `updateContext` (seen in `testClient.getContextUpdates()` and on the status line), **Read
every config** including its two fallbacks, and **Close client**, after which the test client's own
controls change nothing. **Every API** is left alone: `ApiTour` builds a real client with a stand-in
key, which would reach out to ConfigDirector.

The SDK calls watches, listeners and completion callbacks back on the main thread, which
Robolectric only runs when the test idles it, so the test calls
`shadowOf(Looper.getMainLooper()).idle()` after every change. Robolectric and what it brings sit on
the test classpath only; the app itself still has no Kotlin sources and no AndroidX.

Run them with `./gradlew :samples:configdirector-android:java:testDebugUnitTest -PuseLocalSdk`.
[`check-java-tests-ran.sh`](../../../.github/scripts/check-java-tests-ran.sh), which CI and the
pre-push hook run after the build, fails when these tests did not run, as it does for the SDK's own
Java tests.
