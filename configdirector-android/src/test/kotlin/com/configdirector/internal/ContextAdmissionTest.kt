package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextAdmissionTest {

    @Test
    fun `admits a context without a context kind`() {
        val context = ConfigDirectorContext.build { id("42") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `admits no context`() {
        assertThat(admissionOf(null)).isEqualTo(ContextAdmission.Admitted(null))
    }

    @Test
    fun `admits a context kind of lowercase letters, digits, dashes and underscores`() {
        val context = ConfigDirectorContext.build { id("42"); kind("point_of-sale2") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `admits a context kind of 64 characters`() {
        val context = ConfigDirectorContext.build { id("42"); kind("d".repeat(64)) }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `ignores an empty context kind`() {
        val context = ConfigDirectorContext.build { id("42"); kind("") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.IgnoredForAnInvalidContextKind(""))
    }

    @Test
    fun `ignores a context kind of 65 characters`() {
        val context = ConfigDirectorContext.build { id("42"); kind("d".repeat(65)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredForAnInvalidContextKind("d".repeat(65)))
    }

    @Test
    fun `ignores a context kind with an uppercase letter`() {
        val context = ConfigDirectorContext.build { id("42"); kind("Device") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.IgnoredForAnInvalidContextKind("Device"))
    }

    @Test
    fun `ignores a context kind it would have to trim`() {
        val context = ConfigDirectorContext.build { id("42"); kind("device ") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.IgnoredForAnInvalidContextKind("device "))
    }

    @Test
    fun `ignores a context kind with a letter outside ASCII`() {
        val context = ConfigDirectorContext.build { id("42"); kind("dévice") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.IgnoredForAnInvalidContextKind("dévice"))
    }
}
