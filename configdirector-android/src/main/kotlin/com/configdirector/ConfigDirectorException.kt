package com.configdirector

/** Base class for every exception thrown by the ConfigDirector SDK. */
public open class ConfigDirectorException internal constructor(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/** Thrown when an argument has an unusable value, such as a negative timeout. */
public class ConfigDirectorValidationException internal constructor(
    message: String,
) : ConfigDirectorException(message, null)
