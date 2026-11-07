package com.configdirector.internal.testing

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorTestingApi

@ConfigDirectorTestingApi
public fun interface TrackedEventRecorder {
    public fun record(name: String, context: ConfigDirectorContext?, value: Number?, details: Map<String, Any?>?)
}
