package com.configdirector

import com.google.common.truth.Truth.assertThat
import java.util.Date
import org.junit.Assert.assertThrows
import org.junit.Test

class OptionsTest {

    @Test
    fun `connects by streaming with a 60 second poll and a 3 second timeout by default`() {
        val connection = ConnectionOptions.defaults()

        assertThat(connection.mode).isEqualTo(ConnectionMode.STREAMING)
        assertThat(connection.pollingIntervalMillis).isEqualTo(60_000)
        assertThat(connection.timeoutMillis).isEqualTo(3_000)
        assertThat(connection.baseUrl).isNull()
        assertThat(connection.pausesWhileBackgrounded).isTrue()
    }

    @Test
    fun `builds connection options from the Kotlin DSL`() {
        val connection = ConnectionOptions.build {
            mode(ConnectionMode.POLLING)
            pollingIntervalMillis(120_000)
            timeoutMillis(5_000)
            baseUrl("https://proxy.example.com")
            pausesWhileBackgrounded(false)
        }

        assertThat(connection.mode).isEqualTo(ConnectionMode.POLLING)
        assertThat(connection.pollingIntervalMillis).isEqualTo(120_000)
        assertThat(connection.timeoutMillis).isEqualTo(5_000)
        assertThat(connection.baseUrl).isEqualTo("https://proxy.example.com")
        assertThat(connection.pausesWhileBackgrounded).isFalse()
    }

    @Test
    fun `keeps a polling interval below the minimum as configured`() {
        val connection = ConnectionOptions.build { pollingIntervalMillis(10_000) }

        assertThat(connection.pollingIntervalMillis).isEqualTo(10_000)
    }

    @Test
    fun `keeps a zero polling interval as configured`() {
        val connection = ConnectionOptions.build { pollingIntervalMillis(0) }

        assertThat(connection.pollingIntervalMillis).isEqualTo(0)
    }

    @Test
    fun `keeps a negative polling interval as configured`() {
        val connection = ConnectionOptions.build { pollingIntervalMillis(-1) }

        assertThat(connection.pollingIntervalMillis).isEqualTo(-1)
    }

    @Test
    fun `accepts a polling interval of exactly 30 seconds`() {
        val connection = ConnectionOptions.build { pollingIntervalMillis(30_000) }

        assertThat(connection.pollingIntervalMillis).isEqualTo(30_000)
    }

    @Test
    fun `rejects a negative timeout`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build { timeoutMillis(-1) }
        }

        assertThat(failure).hasMessageThat().contains("timeoutMillis '-1'")
    }

    @Test
    fun `rejects a timeout longer than the HTTP client accepts`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build { timeoutMillis(Int.MAX_VALUE.toLong() + 1) }
        }

        assertThat(failure).hasMessageThat().contains("longest the HTTP client accepts")
    }

    @Test
    fun `rejects a base URL that names no host`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build { baseUrl("/configs") }
        }

        assertThat(failure).hasMessageThat().contains("absolute and name a host")
    }

    @Test
    fun `rejects a malformed base URL`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build { baseUrl("https://proxy example.com") }
        }

        assertThat(failure).hasMessageThat().contains("Invalid base URL")
    }

    @Test
    fun `reads a blank base URL as the ConfigDirector service`() {
        val connection = ConnectionOptions.build { baseUrl("  ") }

        assertThat(connection.baseUrl).isEqualTo("  ")
    }

    @Test
    fun `builds offline connection options with values from the Kotlin DSL`() {
        val connection = ConnectionOptions.build {
            mode(ConnectionMode.OFFLINE)
            values(mapOf("new-checkout" to true, "max-items" to 20))
        }

        assertThat(connection.mode).isEqualTo(ConnectionMode.OFFLINE)
        assertThat(connection.values).containsExactly("new-checkout", true, "max-items", 20)
    }

    @Test
    fun `keeps the values it was built with when the map changes afterwards`() {
        val values = mutableMapOf<String, Any>("new-checkout" to true)
        val connection = ConnectionOptions.build {
            mode(ConnectionMode.OFFLINE)
            values(values)
        }

        values["new-checkout"] = false
        values["max-items"] = 20

        assertThat(connection.values).containsExactly("new-checkout", true)
    }

    @Test
    fun `tells apart connection options that differ only in their values`() {
        val newCheckoutOn = ConnectionOptions.build {
            mode(ConnectionMode.OFFLINE)
            values(mapOf("new-checkout" to true))
        }
        val newCheckoutOff = ConnectionOptions.build {
            mode(ConnectionMode.OFFLINE)
            values(mapOf("new-checkout" to false))
        }

        assertThat(newCheckoutOn).isNotEqualTo(newCheckoutOff)
    }

    @Test
    fun `rejects values given in streaming mode`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build {
                mode(ConnectionMode.STREAMING)
                values(mapOf("new-checkout" to true))
            }
        }

        assertThat(failure).hasMessageThat().contains("only in OFFLINE mode, but the mode is STREAMING")
    }

    @Test
    fun `rejects values given in polling mode`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build {
                mode(ConnectionMode.POLLING)
                values(mapOf("new-checkout" to true))
            }
        }

        assertThat(failure).hasMessageThat().contains("only in OFFLINE mode, but the mode is POLLING")
    }

    @Test
    fun `rejects an offline value of a type no config has`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build {
                mode(ConnectionMode.OFFLINE)
                values(mapOf("launch-date" to Date(0)))
            }
        }

        assertThat(failure).hasMessageThat().contains("Invalid value for 'launch-date'")
    }

    @Test
    fun `rejects an offline value that is not finite`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build {
                mode(ConnectionMode.OFFLINE)
                values(mapOf("sample-rate" to Double.NaN))
            }
        }

        assertThat(failure).hasMessageThat().contains("Invalid value for 'sample-rate'")
    }

    @Test
    fun `rejects an offline value under a blank key`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build {
                mode(ConnectionMode.OFFLINE)
                values(mapOf(" " to true))
            }
        }

        assertThat(failure).hasMessageThat().contains("Invalid config key ' '")
    }

    @Test
    fun `leaves the timeout unchecked in offline mode`() {
        val connection = ConnectionOptions.build {
            mode(ConnectionMode.OFFLINE)
            timeoutMillis(-1)
        }

        assertThat(connection.timeoutMillis).isEqualTo(-1)
    }

    @Test
    fun `leaves the base URL unchecked in offline mode`() {
        val connection = ConnectionOptions.build {
            mode(ConnectionMode.OFFLINE)
            baseUrl("/configs")
        }

        assertThat(connection.baseUrl).isEqualTo("/configs")
    }

    @Test
    fun `carries default metadata, connection and logger`() {
        val options = ClientOptions.defaults()

        assertThat(options.metadata).isEqualTo(Metadata.empty())
        assertThat(options.connection).isEqualTo(ConnectionOptions.defaults())
        assertThat(options.logger).isInstanceOf(AndroidLogger::class.java)
        assertThat(options.logger.level).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `builds client options from the Kotlin DSL`() {
        val logger = RecordingLogger(LogLevel.DEBUG)

        val options = ClientOptions.build {
            metadata("Checkout", "4.2.0")
            connection { mode(ConnectionMode.POLLING) }
            logger(logger)
        }

        assertThat(options.metadata).isEqualTo(Metadata("Checkout", "4.2.0"))
        assertThat(options.connection.mode).isEqualTo(ConnectionMode.POLLING)
        assertThat(options.logger).isSameInstanceAs(logger)
    }

    @Test
    fun `leaves both metadata fields to the application when empty`() {
        val metadata = Metadata.empty()

        assertThat(metadata.appName).isNull()
        assertThat(metadata.appVersion).isNull()
        assertThat(metadata).isEqualTo(Metadata())
        assertThat(metadata.hashCode()).isEqualTo(Metadata().hashCode())
    }
}
