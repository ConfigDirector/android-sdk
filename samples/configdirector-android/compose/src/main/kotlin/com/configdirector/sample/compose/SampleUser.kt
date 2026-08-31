package com.configdirector.sample.compose

import com.configdirector.ConfigDirectorContext

/**
 * The identities the sample can evaluate configs against. Switching between them calls
 * `updateContext`, which reconnects and re-evaluates every config.
 */
enum class SampleUser(val label: String, val context: ConfigDirectorContext) {
    CONFIGURED("Configured", configuredContext()),

    BETA_TESTER(
        "Beta tester",
        ConfigDirectorContext.build {
            id("beta-tester")
            name("Beta Tester")
            trait("role", "beta")
        },
    ),

    ANONYMOUS("Anonymous", ConfigDirectorContext.build { anonymous(true) }),
}

/** The identity from `local.properties`. With none set, configs are evaluated without a context. */
private fun configuredContext() = ConfigDirectorContext.build {
    id(BuildConfig.USER_ID.ifEmpty { null })
    name(BuildConfig.USER_NAME.ifEmpty { null })
    BuildConfig.USER_ROLE.ifEmpty { null }?.let { role -> trait("role", role) }
}
