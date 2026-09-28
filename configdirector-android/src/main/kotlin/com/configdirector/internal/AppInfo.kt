package com.configdirector.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.configdirector.ConfigDirectorLogger
import com.configdirector.Metadata
import com.configdirector.debug
import com.configdirector.info

internal fun Metadata.filledFromApplication(
    androidContext: Context,
    logger: ConfigDirectorLogger,
): Metadata {
    val metadata = if (appName != null && appVersion != null) {
        this
    } else {
        val detected = readApplicationMetadata(androidContext, logger)
        Metadata(appName ?: detected.appName, appVersion ?: detected.appVersion)
    }
    logMissingFields(metadata, logger)
    return metadata
}

private fun readApplicationMetadata(androidContext: Context, logger: ConfigDirectorLogger): Metadata =
    try {
        val packageManager = androidContext.packageManager
        val packageInfo = packageManager.packageInfoFor(androidContext.packageName)
        Metadata(
            appName = packageInfo.applicationInfo?.loadLabel(packageManager)?.toString()
                ?.takeUnless { it.isBlank() },
            appVersion = packageInfo.versionName?.takeUnless { it.isBlank() },
        )
    } catch (failure: Exception) {
        logger.debug(failure) { "Failed to read the app name and version from the application" }
        Metadata.empty()
    }

private fun PackageManager.packageInfoFor(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }

private fun logMissingFields(metadata: Metadata, logger: ConfigDirectorLogger) {
    val missing = listOfNotNull(
        "name".takeIf { metadata.appName == null },
        "version".takeIf { metadata.appVersion == null },
    )
    if (missing.isEmpty()) return

    val pronoun = if (missing.size == 1) "it" else "them"
    logger.info {
        "The ConfigDirector SDK could not find an app ${missing.joinToString(" and ")}, so " +
            "targeting rules that use $pronoun will not match. Provide $pronoun through " +
            "ClientOptions.metadata."
    }
}
