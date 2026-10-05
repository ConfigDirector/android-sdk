# ConfigDirector OpenFeature provider sample

A Jetpack Compose app reading flags through the [OpenFeature Kotlin SDK](https://openfeature.dev/docs/reference/sdks/client/kotlin)
with the ConfigDirector provider registered. It reads the same configs and offers the same three
identities as [the SDK samples](../configdirector-android/README.md), so the same dashboard project
drives all of them; the difference is that every read goes through `OpenFeatureAPI.getClient()`.

## Running it

Start an emulator or attach a device, then, from the repository root:

```sh
./gradlew :samples:configdirector-openfeature-android-provider:compose:installDebug -PuseLocalSdk
adb shell am start -n com.configdirector.sample.openfeature/.MainActivity
```

`-PuseLocalSdk` builds against the provider module in this repository. Leave it off to build
against the release in the ConfigDirector Maven repository instead; see
[which artifact it builds against](#which-artifact-it-builds-against).

The key and the identity come from `local.properties`, exactly as for the SDK samples:

```properties
configdirector.clientSdkKey=YOUR-KEY
configdirector.userId=
configdirector.userName=
configdirector.userRole=
```

Turn the SDK's own logging up by watching its tag, which the app sets to `DEBUG`:

```sh
adb logcat -s ConfigDirector:V
```

## What it shows

Each flag is read with its details, so the row shows the value, the reason, and the variant or the
error code. A flag ConfigDirector served reads `TARGETING_MATCH` with the served value's id; before
the provider is ready every flag is its default with `PROVIDER_NOT_READY`, and a key the project
does not have is its default with `FLAG_NOT_FOUND`.

`json-value-config` appears twice: read with a string default it serves the raw document, and read
with a `Value.Structure` default it serves the parsed one. `integer-config` appears twice for the
same reason.

The header follows `OpenFeatureAPI.statusFlow`, and the flags are re-read whenever the provider
emits a configuration change or the status moves, which is how an edit in the dashboard reaches the
screen without restarting.

The identity chips call `OpenFeatureAPI.setEvaluationContext`. The provider sends the targeting key
as the user's id, `name` as the name, the `traits` structure as the traits, and `anonymous` as the
anonymous flag, so a targeting rule written against `role` behaves as it does in the other samples.

## Tests

[`SampleScreenTest`](compose/src/test/kotlin/com/configdirector/sample/openfeature/SampleScreenTest.kt)
renders `SampleScreen` under Robolectric with `createComposeRule`, after registering the provider
with `OpenFeatureAPI` the way `SampleApplication` does, except that the provider is given the client
of a **test client** from `com.configdirector:android-sdk-testing`: the SDK's real client over an
in-memory connection the test controls, so nothing connects and no key is needed. Robolectric is
told to run a plain `Application` instead of `SampleApplication`, so no real provider is registered.

```kotlin
@OptIn(ConfigDirectorProviderTestingApi::class)
...
OpenFeatureAPI.setProviderAndWait(ConfigDirectorProvider(testClient.client), SampleUser.BETA_TESTER.context)
compose.setContent { SampleScreen() }
```

The tests cover every row with its `TARGETING_MATCH` reason once the provider is ready, the
defaults with `PROVIDER_NOT_READY` while the test client holds initialization, a row re-rendering
after `testClient.setValue` (the provider reports a configuration change, which the screen re-reads
on), `FLAG_NOT_FOUND` after `testClient.removeValue`, and the identity chips reaching the client
through `setEvaluationContext`, seen in `testClient.contextUpdates`.

`OpenFeatureAPI` is one global, so each test shuts it down afterwards. The provider hands its events
to OpenFeature through the client's `events` flow on the main looper, which Robolectric only runs
when the test lets it, so the test idles the looper and waits for the composition to settle after
every change.

Run them with
`./gradlew :samples:configdirector-openfeature-android-provider:compose:testDebugUnitTest -PuseLocalSdk`.

## Which artifact it builds against

```kotlin
implementation("com.configdirector:openfeature-android-provider:1.2.0")
testImplementation("com.configdirector:android-sdk-testing:1.6.0")
```

The provider depends on the OpenFeature Kotlin SDK and the ConfigDirector SDK and re-exposes both,
so the sample gets everything from that one line.

The testing tools must be the same version as the SDK the provider brings, which `createTestClient`
checks at runtime.

Both come from the ConfigDirector Maven repository that the repository's `settings.gradle.kts`
declares. The versions deliberately lag the ones in `gradle.properties` between a version bump and
the release that publishes them, and naming a version that is not in the repository yet leaves the
lines unresolvable: pass `-PuseLocalSdk` to build against the modules in this repository, which is
what CI and the pre-push hook do.

## minSdk and Java

The provider runs on API 21 and ships Java 11 bytecode. The sample is on API 23 and Java 11, for
the same reasons as [the Compose SDK sample](../configdirector-android/README.md#they-do-not-share-a-minsdk).
