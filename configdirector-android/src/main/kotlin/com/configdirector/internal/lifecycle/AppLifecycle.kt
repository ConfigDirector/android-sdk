package com.configdirector.internal.lifecycle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.configdirector.ConfigDirectorLogger
import com.configdirector.warn
import java.util.concurrent.atomic.AtomicReference

internal enum class AppLifecyclePhase { FOREGROUND, BACKGROUND }

internal interface AppLifecycleObserver {
    fun start(onChange: (AppLifecyclePhase) -> Unit)

    fun stop()
}

/**
 * The observer for [androidContext], or one that reports nothing when the application cannot be
 * reached from it, which is the case in a unit test running without an Android environment.
 */
internal fun appLifecycleObserver(
    androidContext: Context,
    logger: ConfigDirectorLogger,
): AppLifecycleObserver {
    val application = androidContext.applicationContext as? Application
    if (application == null) {
        logger.warn {
            "The context given to the client does not belong to an Application, so the SDK cannot " +
                "tell when the app is backgrounded. The connection will be left running; pause it " +
                "yourself with pauseNetwork() and resumeNetwork()."
        }
        return object : AppLifecycleObserver {
            override fun start(onChange: (AppLifecyclePhase) -> Unit) = Unit

            override fun stop() = Unit
        }
    }

    return ActivityLifecycleObserver(application)
}

/**
 * Tells the phase from the activities that are started.
 *
 * A configuration change destroys the last activity before it starts the one replacing it, so
 * counting alone would report a rotation as the app being backgrounded.
 */
internal class ForegroundTracker {
    private val lock = Any()
    private var startedActivities = 0
    private var isBackgrounded = true

    /** The phase the process moved to, or null when it stayed where it was. */
    fun activityStarted(): AppLifecyclePhase? = synchronized(lock) {
        startedActivities += 1
        if (!isBackgrounded) return null

        isBackgrounded = false
        AppLifecyclePhase.FOREGROUND
    }

    /** See [activityStarted]. */
    fun activityStopped(isChangingConfigurations: Boolean): AppLifecyclePhase? = synchronized(lock) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (isBackgrounded || isChangingConfigurations || startedActivities > 0) return null

        isBackgrounded = true
        AppLifecyclePhase.BACKGROUND
    }
}

private class ActivityLifecycleObserver(
    private val application: Application,
) : AppLifecycleObserver {

    private val registered = AtomicReference<Application.ActivityLifecycleCallbacks?>(null)

    override fun start(onChange: (AppLifecyclePhase) -> Unit) {
        stop()

        val callbacks = TrackingCallbacks(onChange)
        registered.set(callbacks)
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun stop() {
        registered.getAndSet(null)?.let(application::unregisterActivityLifecycleCallbacks)
    }
}

private class TrackingCallbacks(
    private val onChange: (AppLifecyclePhase) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private val tracker = ForegroundTracker()

    override fun onActivityStarted(activity: Activity) {
        tracker.activityStarted()?.let(onChange)
    }

    override fun onActivityStopped(activity: Activity) {
        tracker.activityStopped(activity.isChangingConfigurations)?.let(onChange)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
