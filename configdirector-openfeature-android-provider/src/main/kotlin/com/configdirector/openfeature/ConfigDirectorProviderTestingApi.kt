package com.configdirector.openfeature

/**
 * Marks the constructor through which a test hands [ConfigDirectorProvider] a client of its own:
 * usually the `client` of a test client from `com.configdirector:android-sdk-testing`.
 *
 * Application code creates the provider with a client SDK key, which is why reaching this
 * constructor takes an explicit `@OptIn(ConfigDirectorProviderTestingApi::class)`.
 */
@RequiresOptIn(
    message = "This constructor is for tests, which pass it the client of a test client from " +
        "com.configdirector:android-sdk-testing. Application code creates the provider with a " +
        "client SDK key.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
@MustBeDocumented
public annotation class ConfigDirectorProviderTestingApi
