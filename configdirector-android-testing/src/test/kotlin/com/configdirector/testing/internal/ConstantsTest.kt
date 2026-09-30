package com.configdirector.testing.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConstantsTest {

    // The artifact refuses to run against an SDK of another version, and the version it compares
    // against is only right if it is the version the artifact is published under.
    @Test
    fun `carries the version it is published under`() {
        assertThat(Constants.TESTING_VERSION).isEqualTo(System.getProperty("configdirector.publishedVersion"))
    }
}
