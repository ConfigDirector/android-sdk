package com.configdirector

import com.google.common.truth.Truth.assertThat
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
            pollingIntervalMillis(30_000)
            timeoutMillis(5_000)
            baseUrl("https://proxy.example.com")
            pausesWhileBackgrounded(false)
        }

        assertThat(connection.mode).isEqualTo(ConnectionMode.POLLING)
        assertThat(connection.pollingIntervalMillis).isEqualTo(30_000)
        assertThat(connection.timeoutMillis).isEqualTo(5_000)
        assertThat(connection.baseUrl).isEqualTo("https://proxy.example.com")
        assertThat(connection.pausesWhileBackgrounded).isFalse()
    }

    @Test
    fun `rejects a polling interval that would never come round`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConnectionOptions.build { pollingIntervalMillis(0) }
        }

        assertThat(failure).hasMessageThat().contains("pollingIntervalMillis '0'")
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
            connection { mode(ConnectionMode.ONE_TIME) }
            logger(logger)
        }

        assertThat(options.metadata).isEqualTo(Metadata("Checkout", "4.2.0"))
        assertThat(options.connection.mode).isEqualTo(ConnectionMode.ONE_TIME)
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
