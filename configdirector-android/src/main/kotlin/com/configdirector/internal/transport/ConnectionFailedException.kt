package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorException

/** The connection to the ConfigDirector server failed, carrying the HTTP status when there was one. */
internal class ConnectionFailedException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : ConfigDirectorException(message, cause)

/**
 * Whether an HTTP status means the request itself is wrong, an invalid SDK key for instance, so
 * retrying it would fail the same way.
 */
internal fun Int.isFatalHttpStatus(): Boolean = this in 400..499
