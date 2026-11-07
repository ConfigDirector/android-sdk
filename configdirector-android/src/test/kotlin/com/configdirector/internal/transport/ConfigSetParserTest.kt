package com.configdirector.internal.transport

import com.configdirector.Variation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigSetParserTest {

    @Test
    fun `reads an unnamed variation with its id and no name`() {
        val configSet = parseConfigSet(checkoutCtaPayload(""""variation":{"id":"variation-1","name":null}"""))

        assertThat(configSet.configs.getValue("checkout-cta").variation).isEqualTo(Variation("variation-1"))
    }

    @Test
    fun `reads a variation whose name is empty with its id and no name`() {
        val configSet = parseConfigSet(checkoutCtaPayload(""""variation":{"id":"variation-1","name":""}"""))

        assertThat(configSet.configs.getValue("checkout-cta").variation).isEqualTo(Variation("variation-1"))
    }

    @Test
    fun `reads no variation from a payload entry whose variation has no id`() {
        val configSet = parseConfigSet(checkoutCtaPayload(""""variation":{"name":"treatment-a"}"""))

        val config = configSet.configs.getValue("checkout-cta")
        assertThat(config.value).isEqualTo("Try it free")
        assertThat(config.variation).isNull()
    }

    private fun checkoutCtaPayload(entryFields: String): String =
        """{"kind":"full","configs":{"checkout-cta":{"id":"checkout-cta-id","key":"checkout-cta",""" +
            """"type":"string","value":"Try it free","valueId":"checkout-cta-value",$entryFields}}}"""
}
