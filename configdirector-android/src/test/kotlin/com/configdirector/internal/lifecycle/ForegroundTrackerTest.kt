package com.configdirector.internal.lifecycle

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundTrackerTest {

    private val tracker = ForegroundTracker()

    @Test
    fun `reports the foreground when the first activity starts`() {
        assertThat(tracker.activityStarted()).isEqualTo(AppLifecyclePhase.FOREGROUND)
    }

    @Test
    fun `reports the background when the last activity stops`() {
        tracker.activityStarted()

        assertThat(tracker.activityStopped(isChangingConfigurations = false))
            .isEqualTo(AppLifecyclePhase.BACKGROUND)
    }

    @Test
    fun `stays in the foreground while another activity is still started`() {
        tracker.activityStarted()
        tracker.activityStarted()

        assertThat(tracker.activityStopped(isChangingConfigurations = false)).isNull()
    }

    @Test
    fun `stays in the foreground across a rotation`() {
        tracker.activityStarted()

        // A configuration change stops the last activity before it starts its replacement, so the
        // count reaches zero without the app going anywhere.
        assertThat(tracker.activityStopped(isChangingConfigurations = true)).isNull()
        assertThat(tracker.activityStarted()).isNull()
    }

    @Test
    fun `reports each phase once`() {
        tracker.activityStarted()
        tracker.activityStopped(isChangingConfigurations = false)

        assertThat(tracker.activityStopped(isChangingConfigurations = false)).isNull()
        assertThat(tracker.activityStarted()).isEqualTo(AppLifecyclePhase.FOREGROUND)
    }

    @Test
    fun `reports the foreground again after the app comes back`() {
        tracker.activityStarted()
        tracker.activityStopped(isChangingConfigurations = false)
        tracker.activityStarted()

        assertThat(tracker.activityStopped(isChangingConfigurations = false))
            .isEqualTo(AppLifecyclePhase.BACKGROUND)
    }
}
