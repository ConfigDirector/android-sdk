package com.configdirector

import android.app.Activity
import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The client through a real activity's lifecycle: what a consuming app does to it is start and stop
 * activities, and everything the SDK knows about being backgrounded comes from those.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClientLifecycleTest {

    private val server = FakeSdkServer()
    private val logger = RecordingLogger()
    private var client: ConfigDirectorClient? = null
    private var activity: ActivityController<Activity>? = null

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        activity?.close()
        client?.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun client(pausesWhileBackgrounded: Boolean = true): ConfigDirectorClient =
        ConfigDirectorClient(
            RuntimeEnvironment.getApplication(),
            "client-sdk-key",
            ClientOptions.build {
                logger(logger)
                connection {
                    baseUrl(server.baseUrl)
                    pausesWhileBackgrounded(pausesWhileBackgrounded)
                }
            },
        ).also { client = it }

    private fun foreground(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).setup().also { activity = it }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    @Test
    fun `pauses the connection when the app is backgrounded`() = runBlocking {
        val client = client()
        val screen = foreground()
        client.initialize()
        assertThat(client.isReady).isTrue()

        screen.pause().stop()

        waitFor("the client to stop being ready") { !client.isReady }
    }

    @Test
    fun `reconnects when the app returns to the foreground`() = runBlocking {
        val client = client()
        val screen = foreground()
        client.initialize()
        screen.pause().stop()
        waitFor("the connection to pause") { !client.isReady }

        screen.start().resume()

        waitFor("the client to be ready again") { client.isReady }
    }

    @Test
    fun `keeps the connection when the app is told not to pause it`() {
        val client = client(pausesWhileBackgrounded = false)
        val screen = foreground()
        runBlocking { client.initialize() }

        screen.pause().stop()

        Thread.sleep(200)
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `leaves a client that never connected alone`() {
        client()
        val screen = foreground()

        screen.pause().stop()
        screen.start().resume()

        // Resuming a connection the app never asked for would connect a client that was never
        // initialized.
        Thread.sleep(200)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `reports what it collected before the app goes away`() = runBlocking {
        val client = client()
        val screen = foreground()
        client.initialize()
        client.getBoolean("dark-mode", false)

        screen.pause().stop()

        waitFor("the telemetry report") { server.telemetryReports.isNotEmpty() }
    }

    @Test
    fun `stops watching the app once it is closed`() {
        val application = RuntimeEnvironment.getApplication()
        val client = client()
        assertThat(registeredLifecycleCallbacks(application)).isNotEmpty()

        client.close()

        // A closed client the application still holds a callback to is a leak of everything the
        // client owns.
        assertThat(registeredLifecycleCallbacks(application)).isEmpty()
    }

    private fun registeredLifecycleCallbacks(application: Application): List<Any> {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        return (field.get(application) as? Collection<*>)?.filterNotNull().orEmpty()
    }

    @Test
    fun `reconnects under the reason a resumed connection has`() = runBlocking {
        val client = client()
        val events = CopyOnWriteArrayList<ClientEvent>()
        client.initialize()
        client.addEventListener { event -> events += event }

        client.pauseNetwork()
        assertThat(client.isReady).isFalse()
        client.resumeNetwork()

        waitFor("the ready event") { events.filterIsInstance<ClientEvent.Ready>().isNotEmpty() }
        assertThat(events.filterIsInstance<ClientEvent.Ready>().first().reason)
            .isEqualTo(ConnectReason.NETWORK_RESUME)
    }
}
