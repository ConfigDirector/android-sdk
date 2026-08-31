package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingLogger(override val level: LogLevel) : ConfigDirectorLogger {
    val messages: MutableList<String> = mutableListOf()
    val errors: MutableList<Throwable?> = mutableListOf()

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        messages += "$level: $message"
        errors += error
    }
}

class ConfigDirectorLoggerTest {

    @Test
    fun `writes messages at or above its level`() {
        val logger = RecordingLogger(LogLevel.INFO)

        logger.error { "error" }
        logger.warn { "warn" }
        logger.info { "info" }
        logger.debug { "debug" }

        assertThat(logger.messages).containsExactly("ERROR: error", "WARN: warn", "INFO: info")
    }

    @Test
    fun `drops every message when off`() {
        val logger = RecordingLogger(LogLevel.OFF)

        logger.error { "error" }

        assertThat(logger.messages).isEmpty()
    }

    @Test
    fun `leaves a dropped message unbuilt`() {
        val logger = RecordingLogger(LogLevel.WARN)
        var built = false

        logger.debug { built = true; "debug" }

        assertThat(built).isFalse()
    }

    @Test
    fun `carries the error alongside the message`() {
        val logger = RecordingLogger(LogLevel.WARN)
        val failure = RuntimeException("boom")

        logger.error(failure) { "failed" }

        assertThat(logger.errors).containsExactly(failure)
    }
}
