package com.configdirector.sample.compose

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.configdirector.compose.ConfigDirectorProvider
import com.configdirector.testing.createTestClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The screen over a test client from the SDK's testing tools, wrapped in the production
 * `ConfigDirectorProvider` as `MainActivity` does. A plain `Application` stands in for
 * `SampleApplication`, so no real client is built and nothing connects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SampleScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val testClient = createTestClient(values = SAMPLE_VALUES)

    @After
    fun tearDown() {
        testClient.client.close()
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun showScreen() {
        compose.setContent { ConfigDirectorProvider(testClient.client) { SampleScreen() } }
        compose.waitForIdle()
    }

    private fun initialize() {
        runBlocking { testClient.client.initialize(SampleUser.CONFIGURED.context) }
        settle()
    }

    @Test
    fun `shows the defaults until the client is ready`() {
        showScreen()

        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("Friday").assertExists()
        compose.onNodeWithText("10").assertExists()
        compose.onAllNodesWithText("{}").assertCountEquals(2)

        initialize()

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Monday").assertExists()
    }

    @Test
    fun `shows every config the test client serves`() {
        showScreen()
        initialize()

        compose.onNodeWithText("false").assertExists()
        compose.onNodeWithText("true").assertExists()
        compose.onNodeWithText("42").assertExists()
        compose.onNodeWithText("Monday").assertExists()
        compose.onNodeWithText("""{"theme":"dark"}""").assertExists()
        compose.onNodeWithText("42.0").assertExists()
        compose.onNodeWithText("{theme=dark}").assertExists()
    }

    @Test
    fun `re-renders a config when the test client changes its value`() {
        showScreen()
        initialize()

        testClient.setValue("day-of-the-week-config", "Tuesday")
        settle()

        compose.onNodeWithText("Tuesday").assertExists()
        compose.onNodeWithText("Monday").assertDoesNotExist()
    }

    @Test
    fun `falls back to the defaults when the test client removes a config`() {
        showScreen()
        initialize()

        testClient.removeValue("integer-config")
        settle()

        compose.onNodeWithText("10").assertExists()
        compose.onNodeWithText("0.0").assertExists()
        compose.onNodeWithText("42").assertDoesNotExist()
    }

    @Test
    fun `switches the identity configs are evaluated against from the chips`() {
        showScreen()
        initialize()

        compose.onNodeWithText("Beta tester").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { testClient.contextUpdates.size == 2 }
        settle()

        assertThat(testClient.contextUpdates.last().id).isEqualTo("beta-tester")
        compose.onNodeWithText("id=beta-tester, name=Beta Tester, traits={role=beta}, anonymous=false")
            .assertExists()
    }

    @Test
    fun `shows Connecting while initialization is held`() {
        testClient.holdInitialization()
        showScreen()

        val initialization = CoroutineScope(Dispatchers.Default).launch { testClient.client.initialize() }
        compose.waitUntil(timeoutMillis = 5_000) { testClient.client.isInitializing }
        settle()
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("Friday").assertExists()

        testClient.completeInitialization()
        runBlocking { initialization.join() }
        settle()

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Monday").assertExists()
    }

    @Test
    fun `keeps the defaults when initialization fails`() {
        testClient.failInitialization()
        showScreen()

        initialize()

        assertThat(testClient.client.isReady).isFalse()
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("Friday").assertExists()
    }

    private companion object {
        val SAMPLE_VALUES: Map<String, Any> = mapOf(
            "temporary-feature-flag" to false,
            "permanent-kill-switch" to true,
            "integer-config" to 42,
            "day-of-the-week-config" to "Monday",
            "json-value-config" to mapOf("theme" to "dark"),
        )
    }
}
