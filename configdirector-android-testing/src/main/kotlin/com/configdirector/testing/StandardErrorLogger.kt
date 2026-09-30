package com.configdirector.testing

import com.configdirector.ConfigDirectorLogger
import com.configdirector.LogLevel

/**
 * A logger writing to standard error, where a test runner shows it. It is the logger of a test
 * client made without one, because the SDK's default writes to logcat, which a plain JVM test does
 * not have.
 *
 * @param level the most verbose level to write; warnings and errors when not given
 */
public class StandardErrorLogger @JvmOverloads constructor(
    override val level: LogLevel = LogLevel.WARN,
) : ConfigDirectorLogger {

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        if (level == LogLevel.OFF) return
        val standardError = System.err
        standardError.println("ConfigDirector $level: $message")
        error?.printStackTrace(standardError)
    }
}
