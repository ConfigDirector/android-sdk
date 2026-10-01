# ConfigDirector OpenFeature Android Provider

[![CI][ci-badge]][ci] [![Maven Central][maven-badge]][maven]

[OpenFeature](https://openfeature.dev) provider for Android and Kotlin, published to Maven Central as
`com.configdirector:configdirector-openfeature-android-provider`. It wraps the
[ConfigDirector Android SDK](../configdirector-android/) for the
[OpenFeature Kotlin SDK](https://openfeature.dev/docs/reference/sdks/client/kotlin), and runs on
Android 5.0 (API 21) and up.

## Install

```kotlin
dependencies {
    implementation("com.configdirector:configdirector-openfeature-android-provider:1.2.0")
}
```

The OpenFeature Kotlin SDK (`dev.openfeature:kotlin-sdk`) and the ConfigDirector Android SDK come
with it as transitive dependencies. The provider ships Java 11 bytecode, which the OpenFeature Kotlin
SDK requires; the ConfigDirector SDK on its own stays on Java 8.

## Retrieve a value

```kotlin
import com.configdirector.openfeature.ConfigDirectorProvider
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI

val provider = ConfigDirectorProvider(applicationContext, "YOUR-CLIENT-SDK-KEY")
OpenFeatureAPI.setProviderAndWait(provider, ImmutableContext(targetingKey = "user-123"))

val client = OpenFeatureAPI.getClient()
val darkMode = client.getBooleanValue("dark-mode", false)
```

The provider takes the same `ClientOptions` as the SDK, for application metadata, the connection
mode and timeout, and logging.

Full details are in the [official documentation](https://docs.configdirector.com/sdks/openfeature/android).

## Test your code

The OpenFeature Kotlin SDK ships no in-memory provider, so to test the code that reads flags through
OpenFeature, create the provider over a **test client** from the SDK's testing tools,
[`configdirector-android-testing`](../configdirector-android-testing/): the SDK's real client
connected to an in-memory server your test controls. The constructor that takes a client sits
behind the `@ConfigDirectorProviderTestingApi` opt-in, which keeps it out of application code:

```kotlin
dependencies {
    testImplementation("com.configdirector:configdirector-android-testing:1.6.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
```

```kotlin
import com.configdirector.openfeature.ConfigDirectorProvider
import com.configdirector.openfeature.ConfigDirectorProviderTestingApi
import com.configdirector.testing.createTestClient
import dev.openfeature.kotlin.sdk.OpenFeatureAPI

@OptIn(ConfigDirectorProviderTestingApi::class)
class CheckoutTest {

    @Before
    fun setUpMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = runBlocking {
        OpenFeatureAPI.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `shows the new checkout`() = runBlocking {
        val testClient = createTestClient(values = mapOf("new-checkout" to true))
        OpenFeatureAPI.setProviderAndWait(ConfigDirectorProvider(testClient.client))
        val client = OpenFeatureAPI.getClient()

        assertThat(client.getBooleanValue("new-checkout", false)).isTrue()

        testClient.setValue("new-checkout", false)
        assertThat(client.getBooleanValue("new-checkout", false)).isFalse()

        testClient.client.close()
    }
}
```

The provider never closes a client it was given, so the test closes it. Full details are in the
[testing section of the official documentation](https://docs.configdirector.com/sdks/openfeature/android#test-your-code).

## Documentation

Refer to the [official documentation for the OpenFeature Android provider](https://docs.configdirector.com/sdks/openfeature/android).

There is also [a quickstart guide for ConfigDirector and any of our SDKs](https://docs.configdirector.com/getting-started/quickstart).

## Sample app

[`samples/configdirector-openfeature-android-provider/compose`](../samples/configdirector-openfeature-android-provider/compose)
is a Jetpack Compose app reading a handful of flags through the OpenFeature client, with the reason
and variant of each, and re-reading them as the provider reports changes.

## Getting Help

- [Ask a question in Discussions](https://github.com/orgs/ConfigDirector/discussions)
- [Contact support](https://www.configdirector.com/support)

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/android-sdk/actions/workflows/configdirector-android.yml/badge.svg
[ci]: https://github.com/ConfigDirector/android-sdk/actions/workflows/configdirector-android.yml
[maven-badge]: https://img.shields.io/maven-central/v/com.configdirector/configdirector-openfeature-android-provider
[maven]: https://central.sonatype.com/artifact/com.configdirector/configdirector-openfeature-android-provider
