package com.configdirector.sample.compose

import com.configdirector.ConfigDirectorContext

/**
 * The identities the sample can evaluate configs against. Switching between them calls
 * `updateContext`, which reconnects and re-evaluates every config.
 */
enum class SampleUser(val label: String, val context: ConfigDirectorContext) {
    CONFIGURED(
        "Configured",
        ConfigDirectorContext.build {
            id("user-123")
            name("Sam")
            trait("plan", "free")
        },
    ),

    PRO(
        "Pro plan",
        ConfigDirectorContext.build {
            id("user-456")
            name("Ada")
            trait("plan", "pro")
            trait("seats", 12)
            trait("beta", true)
        },
    ),

    ANONYMOUS("Anonymous", ConfigDirectorContext.build { anonymous(true) }),
}
