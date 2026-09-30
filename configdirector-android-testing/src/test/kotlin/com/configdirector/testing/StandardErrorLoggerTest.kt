package com.configdirector.testing

import com.configdirector.LogLevel
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Before
import org.junit.Test

class StandardErrorLoggerTest {

    private val standardError = ByteArrayOutputStream()
    private val original = System.err

    @Before
    fun captureStandardError() {
        System.setErr(PrintStream(standardError, true))
    }

    @After
    fun restoreStandardError() {
        System.setErr(original)
    }

    @Test
    fun `passes warnings and errors by default`() {
        assertThat(StandardErrorLogger().level).isEqualTo(LogLevel.WARN)
        assertThat(StandardErrorLogger(LogLevel.DEBUG).level).isEqualTo(LogLevel.DEBUG)
    }

    @Test
    fun `writes the level and the message to standard error`() {
        StandardErrorLogger().log(LogLevel.WARN, "the connection is being retried", null)

        assertThat(standardError.toString()).isEqualTo("ConfigDirector WARN: the connection is being retried\n")
    }

    @Test
    fun `writes the error after the message`() {
        StandardErrorLogger().log(LogLevel.ERROR, "the connection failed", IllegalStateException("status 401"))

        val written = standardError.toString()
        assertThat(written).startsWith("ConfigDirector ERROR: the connection failed\n")
        assertThat(written).contains("java.lang.IllegalStateException: status 401")
        assertThat(written).contains("\tat ")
    }

    @Test
    fun `writes nothing for the off level`() {
        StandardErrorLogger().log(LogLevel.OFF, "dropped", null)

        assertThat(standardError.toString()).isEmpty()
    }
}
