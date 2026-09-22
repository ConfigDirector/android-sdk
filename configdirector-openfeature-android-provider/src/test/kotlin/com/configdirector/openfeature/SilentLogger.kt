package com.configdirector.openfeature

import com.configdirector.ConfigDirectorLogger
import com.configdirector.LogLevel

object SilentLogger : ConfigDirectorLogger {
    override val level: LogLevel = LogLevel.OFF

    override fun log(level: LogLevel, message: String, error: Throwable?) = Unit
}
