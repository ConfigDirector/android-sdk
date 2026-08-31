package com.configdirector

import android.util.Log

/** The verbosity of a [ConfigDirectorLogger]. */
public enum class LogLevel {
    /** Drops every message. */
    OFF,

    /** Failures the SDK could not recover from. */
    ERROR,

    /** Recoverable problems, such as a connection that is being retried. This is the default. */
    WARN,

    /** Lifecycle milestones, such as the client becoming ready. */
    INFO,

    /** Per-request and per-evaluation detail. Useful when diagnosing a problem, noisy otherwise. */
    DEBUG,
}

/**
 * Where the SDK writes its logs. Implement this to route them into your application's own logging.
 */
public interface ConfigDirectorLogger {
    /** Messages more verbose than this level are never passed to [log]. */
    public val level: LogLevel

    /** Writes [message], along with [error] when one is given. */
    public fun log(level: LogLevel, message: String, error: Throwable?)
}

/** The default logger, which writes to logcat under the `ConfigDirector` tag. */
public class AndroidLogger @JvmOverloads constructor(
    override val level: LogLevel = LogLevel.WARN,
) : ConfigDirectorLogger {

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        when (level) {
            LogLevel.OFF -> Unit
            LogLevel.ERROR -> Log.e(TAG, message, error)
            LogLevel.WARN -> Log.w(TAG, message, error)
            LogLevel.INFO -> Log.i(TAG, message, error)
            LogLevel.DEBUG -> Log.d(TAG, message, error)
        }
    }

    private companion object {
        private const val TAG = "ConfigDirector"
    }
}

internal fun ConfigDirectorLogger.error(error: Throwable? = null, message: () -> String) {
    write(LogLevel.ERROR, error, message)
}

internal fun ConfigDirectorLogger.warn(error: Throwable? = null, message: () -> String) {
    write(LogLevel.WARN, error, message)
}

internal fun ConfigDirectorLogger.info(error: Throwable? = null, message: () -> String) {
    write(LogLevel.INFO, error, message)
}

internal fun ConfigDirectorLogger.debug(error: Throwable? = null, message: () -> String) {
    write(LogLevel.DEBUG, error, message)
}

private fun ConfigDirectorLogger.write(
    messageLevel: LogLevel,
    error: Throwable?,
    message: () -> String,
) {
    if (messageLevel > level) return
    log(messageLevel, message(), error)
}
