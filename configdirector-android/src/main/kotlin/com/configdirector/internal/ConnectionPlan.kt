package com.configdirector.internal

import com.configdirector.ConfigDirectorLogger
import com.configdirector.ConfigDirectorValidationException
import com.configdirector.ConnectionMode
import com.configdirector.ConnectionOptions
import com.configdirector.MINIMUM_POLLING_INTERVAL_MILLIS
import com.configdirector.warn

internal enum class LiveConnectionMode { STREAMING, POLLING }

internal sealed class ConnectionPlan {
    class Connect(
        val mode: LiveConnectionMode,
        val clientSdkKey: String,
        val baseUrl: String,
        val pollingIntervalMillis: Long,
    ) : ConnectionPlan()

    class StayOffline(val configs: Map<String, ConfigState>) : ConnectionPlan()
}

internal fun planConnection(
    clientSdkKey: String?,
    connection: ConnectionOptions,
    logger: ConfigDirectorLogger,
): ConnectionPlan = when (connection.mode) {
    ConnectionMode.STREAMING -> planLiveConnection(LiveConnectionMode.STREAMING, clientSdkKey, connection, logger)
    ConnectionMode.POLLING -> planLiveConnection(LiveConnectionMode.POLLING, clientSdkKey, connection, logger)
    ConnectionMode.OFFLINE -> ConnectionPlan.StayOffline(connection.offlineConfigs)
}

private fun planLiveConnection(
    mode: LiveConnectionMode,
    clientSdkKey: String?,
    connection: ConnectionOptions,
    logger: ConfigDirectorLogger,
): ConnectionPlan.Connect {
    if (clientSdkKey.isNullOrBlank()) {
        throw ConfigDirectorValidationException(
            "No client SDK key was provided. The client cannot be created without a valid client SDK key.",
        )
    }

    val baseUrl = connection.baseUrl?.trim().orEmpty().ifEmpty { Constants.CLIENT_BASE_URL }
    if (!baseUrl.startsWith("https://", ignoreCase = true)) {
        logger.warn {
            "The base URL '$baseUrl' is not HTTPS. The client SDK key, every context you " +
                "send, and every config value served back travel in plain text."
        }
    }

    return ConnectionPlan.Connect(
        mode = mode,
        clientSdkKey = clientSdkKey,
        baseUrl = baseUrl,
        pollingIntervalMillis = pollingIntervalMillisFor(mode, connection.pollingIntervalMillis, logger),
    )
}

private fun pollingIntervalMillisFor(
    mode: LiveConnectionMode,
    configuredPollingIntervalMillis: Long,
    logger: ConfigDirectorLogger,
): Long = when (mode) {
    LiveConnectionMode.STREAMING -> configuredPollingIntervalMillis
    LiveConnectionMode.POLLING -> raisedToTheMinimumPollingInterval(configuredPollingIntervalMillis, logger)
}

private fun raisedToTheMinimumPollingInterval(
    configuredPollingIntervalMillis: Long,
    logger: ConfigDirectorLogger,
): Long {
    if (configuredPollingIntervalMillis >= MINIMUM_POLLING_INTERVAL_MILLIS) {
        return configuredPollingIntervalMillis
    }

    logger.warn {
        "pollingIntervalMillis of $configuredPollingIntervalMillis ms is below the minimum " +
            "of $MINIMUM_POLLING_INTERVAL_MILLIS ms. Using $MINIMUM_POLLING_INTERVAL_MILLIS ms."
    }
    return MINIMUM_POLLING_INTERVAL_MILLIS
}
