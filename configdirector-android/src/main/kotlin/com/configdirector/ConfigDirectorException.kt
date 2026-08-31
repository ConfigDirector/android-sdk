package com.configdirector

/** An error raised by the ConfigDirector SDK. */
public open class ConfigDirectorException internal constructor(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)
