package com.configdirector

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ManifestTest {

    // Nothing else catches a missing permission: the SDK builds, and then every connection fails on
    // a device. Declaring it here merges it into the consuming app.
    @Test
    fun `declares the internet permission the SDK cannot work without`() {
        val manifest = File("src/main/AndroidManifest.xml")

        assertThat(manifest.exists()).isTrue()
        assertThat(manifest.readText()).contains("android.permission.INTERNET")
    }
}
