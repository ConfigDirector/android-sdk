package com.configdirector.compose

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.configdirector.testing.createTestClient
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The bindings over the testing artifact's client, as a consumer's Compose test would use them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigValuesTestClientTest {

    @get:Rule
    val compose = createComposeRule()

    private val testClient = createTestClient(values = mapOf("dark-mode" to true))

    @After
    fun tearDown() {
        testClient.client.close()
    }

    /**
     * The SDK hands watches back on the main thread, and Robolectric's main looper only runs when
     * a test lets it. This is the recipe the documentation shows.
     */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun `S16 recomposes with each value the test client serves`() {
        val values = CopyOnWriteArrayList<Boolean>()
        compose.setContent { ConfigDirectorProvider(testClient.client) { values += configValue("dark-mode", false) } }
        compose.waitForIdle()
        assertThat(values.last()).isFalse()

        runBlocking { testClient.client.initialize() }
        settle()
        assertThat(values.last()).isTrue()

        testClient.setValue("dark-mode", false)
        settle()
        assertThat(values).containsExactly(false, true, false).inOrder()
    }
}
