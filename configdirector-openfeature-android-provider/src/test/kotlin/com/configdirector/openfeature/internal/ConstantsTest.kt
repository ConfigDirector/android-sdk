package com.configdirector.openfeature.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConstantsTest {

    // The provider reports this to the server with every request, and the number it reports is only
    // useful if it is the number the artifact was published under.
    @Test
    fun `reports the version it is published under`() {
        assertThat(Constants.PROVIDER_VERSION)
            .isEqualTo(System.getProperty("configdirector.publishedVersion"))
    }
}
