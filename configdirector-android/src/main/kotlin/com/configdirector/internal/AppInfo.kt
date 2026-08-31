package com.configdirector.internal

import com.configdirector.Metadata
import com.configdirector.internal.transport.SdkMetaContext

internal object AppInfo {

    fun metaContext(metadata: Metadata): SdkMetaContext = SdkMetaContext(
        sdkName = Constants.SDK_NAME,
        sdkVersion = Constants.SDK_VERSION,
        appName = metadata.appName,
        appVersion = metadata.appVersion,
        userAgent = USER_AGENT,
    )

    /** The platform name, matching what the other ConfigDirector client SDKs report. */
    private const val USER_AGENT = "Android"
}
