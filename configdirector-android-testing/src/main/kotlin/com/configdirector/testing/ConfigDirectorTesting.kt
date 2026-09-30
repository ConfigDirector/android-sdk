@file:JvmName("ConfigDirectorTesting")

package com.configdirector.testing

import com.configdirector.ConfigDirectorLogger
import com.configdirector.ConfigDirectorTestingApi
import com.configdirector.ConnectionOptions
import com.configdirector.internal.testing.InMemoryConnection
import com.configdirector.testing.internal.Constants

/**
 * Creates a test client: the SDK's real [com.configdirector.ConfigDirectorClient] connected to an
 * in-memory server that the test controls through the returned [TestClient]. Nothing reaches the
 * network, no telemetry is sent, and no Android `Context` is needed, so it runs in a plain JVM
 * unit test as well as under Robolectric or on a device.
 *
 * The client starts uninitialized, like a production client, because the code under test usually
 * owns the call to `initialize`. With nothing held or failed, `initialize` completes at once with
 * the client ready and serving [values].
 *
 * ```kotlin
 * val testClient = createTestClient(values = mapOf("new-checkout" to true, "max-items" to 20))
 * testClient.client.initialize()
 *
 * val checkout = Checkout(testClient.client)
 * assertThat(checkout.isNewCheckoutEnabled).isTrue()
 *
 * testClient.setValue("new-checkout", false)
 * assertThat(checkout.isNewCheckoutEnabled).isFalse()
 * ```
 *
 * From Java, `ConfigDirectorTesting.createTestClient(values)`.
 *
 * @param values the values to serve, keyed by config key; see [TestClient.setValue] for the
 *   accepted types and how each becomes a config
 * @param timeoutMillis the client's connection timeout, which bounds how long a held attempt
 *   waits; the SDK's production default when not given
 * @param logger where the client logs; [StandardErrorLogger] when not given, because the SDK's
 *   own default writes to logcat, which a plain JVM test does not have
 * @throws com.configdirector.ConfigDirectorValidationException if a value cannot be encoded
 * @throws IllegalStateException if this artifact's version differs from the SDK's, which can
 *   only work by accident because it relies on the SDK's internals
 */
@JvmOverloads
public fun createTestClient(
    values: Map<String, Any> = emptyMap(),
    timeoutMillis: Long = ConnectionOptions.builder().build().timeoutMillis,
    logger: ConfigDirectorLogger = StandardErrorLogger(),
): TestClient = createTestClient(values, timeoutMillis, logger, sdkVersion())

@OptIn(ConfigDirectorTestingApi::class)
private fun sdkVersion(): String = InMemoryConnection.sdkVersion

@JvmSynthetic
@OptIn(ConfigDirectorTestingApi::class)
internal fun createTestClient(
    values: Map<String, Any>,
    timeoutMillis: Long,
    logger: ConfigDirectorLogger,
    sdkVersion: String,
): TestClient {
    check(sdkVersion == Constants.TESTING_VERSION) {
        "configdirector-android-testing ${Constants.TESTING_VERSION} requires configdirector-android " +
            "${Constants.TESTING_VERSION}, but $sdkVersion is on the classpath. The testing artifact " +
            "relies on the SDK's internals, so both must be the same version."
    }
    return TestClient(InMemoryConnection(values, timeoutMillis, logger))
}
