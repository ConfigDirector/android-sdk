package com.configdirector.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConstantsTest {

    // The SDK sends this to the server with every request, and the number it sends is only useful
    // if it is the number the artifact was published under.
    @Test
    fun `reports the version it is published under`() {
        assertThat(Constants.SDK_VERSION)
            .isEqualTo(System.getProperty("configdirector.publishedVersion"))
    }
}
