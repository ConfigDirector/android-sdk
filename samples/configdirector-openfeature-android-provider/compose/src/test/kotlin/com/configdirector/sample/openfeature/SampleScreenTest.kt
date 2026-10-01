package com.configdirector.sample.openfeature

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.configdirector.openfeature.ConfigDirectorProvider
import com.configdirector.openfeature.ConfigDirectorProviderTestingApi
import com.configdirector.testing.createTestClient
import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The screen over the provider registered with a test client from the SDK's testing tools, the
 * way `SampleApplication` registers the production one. A plain `Application` stands in for it,
 * so no real client is built and nothing connects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ConfigDirectorProviderTestingApi::class)
class SampleScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val testClient = createTestClient(values = SAMPLE_VALUES)

    @After
    fun tearDown() {
        runBlocking { OpenFeatureAPI.shutdown() }
        testClient.client.close()
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun registerTheProvider() {
        runBlocking {
            OpenFeatureAPI.setProviderAndWait(ConfigDirectorProvider(testClient.client), SampleUser.BETA_TESTER.context)
        }
    }

    private fun showScreen() {
        compose.setContent { SampleScreen() }
        settle()
    }

    private fun waitUntilShown(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            settle()
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `shows every flag the provider resolves`() {
        registerTheProvider()
        showScreen()

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("false").assertExists()
        compose.onNodeWithText("true").assertExists()
        compose.onNodeWithText("42").assertExists()
        compose.onNodeWithText("Monday").assertExists()
        compose.onNodeWithText("""{"theme":"dark"}""").assertExists()
        compose.onNodeWithText("42.0").assertExists()
        compose.onNodeWithText("Structure(structure={theme=String(string=dark)})").assertExists()
        compose.onAllNodesWithText("TARGETING_MATCH", substring = true).assertCountEquals(7)
        compose.onNodeWithText("targetingKey=beta-tester", substring = true).assertExists()
    }

    @Test
    fun `shows the defaults as not ready until the provider is ready`() {
        testClient.holdInitialization()
        OpenFeatureAPI.setProvider(ConfigDirectorProvider(testClient.client), initialContext = SampleUser.BETA_TESTER.context)
        showScreen()

        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("Friday").assertExists()
        compose.onAllNodesWithText("PROVIDER_NOT_READY", substring = true).assertCountEquals(7)

        testClient.completeInitialization()
        compose.waitUntil(timeoutMillis = 5_000) { OpenFeatureAPI.getStatus() == OpenFeatureStatus.Ready }
        waitUntilShown("Monday")

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onAllNodesWithText("TARGETING_MATCH", substring = true).assertCountEquals(7)
    }

    @Test
    fun `re-renders a flag when the test client changes its value`() {
        registerTheProvider()
        showScreen()

        testClient.setValue("day-of-the-week-config", "Tuesday")
        waitUntilShown("Tuesday")

        compose.onNodeWithText("Monday").assertDoesNotExist()
    }

    @Test
    fun `falls back to the default when the test client removes a flag`() {
        registerTheProvider()
        showScreen()

        testClient.removeValue("integer-config")
        waitUntilShown("FLAG_NOT_FOUND", substring = true)

        compose.onNodeWithText("10").assertExists()
        compose.onNodeWithText("0.0").assertExists()
        compose.onAllNodesWithText("FLAG_NOT_FOUND", substring = true).assertCountEquals(2)
        compose.onNodeWithText("42").assertDoesNotExist()
    }

    @Test
    fun `switches the identity flags are evaluated against from the chips`() {
        registerTheProvider()
        showScreen()

        compose.onNodeWithText("Anonymous").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { testClient.contextUpdates.size == 2 }
        waitUntilShown("anonymous=true", substring = true)

        assertThat(testClient.contextUpdates.map { it.id }).containsExactly("beta-tester", null).inOrder()
        assertThat(testClient.contextUpdates.last().isAnonymous).isTrue()
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
