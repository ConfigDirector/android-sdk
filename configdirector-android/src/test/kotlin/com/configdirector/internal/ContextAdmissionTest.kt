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

    @Test
    fun `ignores an id of 257 characters for being too long`() {
        val context = ConfigDirectorContext.build { id("i".repeat(257)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.IDENTIFIER))
    }

    @Test
    fun `admits an id of 256 characters`() {
        val context = ConfigDirectorContext.build { id("i".repeat(256)) }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `admits an id of 200 emoji, counting each emoji as one character`() {
        val context = ConfigDirectorContext.build { id("😀".repeat(200)) }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `ignores a name of 257 characters for being too long`() {
        val context = ConfigDirectorContext.build { id("42"); name("n".repeat(257)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.NAME))
    }

    @Test
    fun `admits a name of 256 characters`() {
        val context = ConfigDirectorContext.build { id("42"); name("n".repeat(256)) }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `ignores traits of 65,537 bytes of JSON for being too large`() {
        val context = ConfigDirectorContext.build { id("42"); traits(traitsOfJsonBytes(65_537)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.TRAITS))
    }

    @Test
    fun `admits traits of exactly 65,536 bytes of JSON`() {
        val context = ConfigDirectorContext.build { id("42"); traits(traitsOfJsonBytes(65_536)) }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `ignores traits of fewer than 65,536 characters but more than 65,536 bytes of JSON`() {
        val context = ConfigDirectorContext.build { id("42"); trait("note", "é".repeat(40_000)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.TRAITS))
    }

    @Test
    fun `counts a whole-number Double in the traits as the integer the SDK sends`() {
        val noteLength = 65_536 - """{"score":3,"note":""}""".length
        val context = ConfigDirectorContext.build {
            id("42")
            traits(linkedMapOf("score" to 3.0, "note" to "x".repeat(noteLength)))
        }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.Admitted(context))
    }

    @Test
    fun `ignores a context over the id and the traits limits for its id`() {
        val context = ConfigDirectorContext.build { id("i".repeat(257)); traits(traitsOfJsonBytes(65_537)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.IDENTIFIER))
    }

    @Test
    fun `ignores a context over the id and the name limits for its id`() {
        val context = ConfigDirectorContext.build { id("i".repeat(257)); name("n".repeat(257)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.IDENTIFIER))
    }

    @Test
    fun `ignores a context over the name and the traits limits for its name`() {
        val context = ConfigDirectorContext.build { id("42"); name("n".repeat(257)); traits(traitsOfJsonBytes(65_537)) }

        assertThat(admissionOf(context))
            .isEqualTo(ContextAdmission.IgnoredOverASizeLimit(ContextSizeLimit.NAME))
    }

    @Test
    fun `ignores a context with an invalid context kind and an id over the limit for its context kind`() {
        val context = ConfigDirectorContext.build { id("i".repeat(257)); kind("Device") }

        assertThat(admissionOf(context)).isEqualTo(ContextAdmission.IgnoredForAnInvalidContextKind("Device"))
    }

    private fun traitsOfJsonBytes(jsonBytes: Int): Map<String, Any?> {
        val jsonWithoutTheNote =
            """{"plan":"pro","seats":12,"ratio":0.5,"beta":true,"manager":null,"regions":["eu","us"],""" +
                """"billing":{"annual":false,"tags":[]},"note":""}"""
        return linkedMapOf(
            "plan" to "pro",
            "seats" to 12,
            "ratio" to 0.5,
            "beta" to true,
            "manager" to null,
            "regions" to listOf("eu", "us"),
            "billing" to linkedMapOf("annual" to false, "tags" to emptyList<Any?>()),
            "note" to "x".repeat(jsonBytes - jsonWithoutTheNote.length),
        )
    }
}
