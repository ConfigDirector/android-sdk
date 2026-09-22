@file:OptIn(ConfigDirectorWrapperApi::class)

package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SdkIdentityTest {

    @Test
    fun `names the OpenFeature provider at the version it is given`() {
        val identity = SdkIdentity.openFeatureProvider("2.1.0")

        assertThat(identity.name).isEqualTo("android-openfeature-client-provider")
        assertThat(identity.version).isEqualTo("2.1.0")
    }

    @Test
    fun `rejects a blank provider version`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            SdkIdentity.openFeatureProvider("   ")
        }

        assertThat(failure).hasMessageThat().contains("No version was provided")
    }

    @Test
    fun `spells itself the way a user agent would`() {
        assertThat(SdkIdentity.openFeatureProvider("2.1.0").toString())
            .isEqualTo("android-openfeature-client-provider/2.1.0")
    }
}
