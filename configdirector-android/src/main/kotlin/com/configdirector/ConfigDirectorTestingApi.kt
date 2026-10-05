package com.configdirector

/**
 * Marks the API through which the ConfigDirector testing artifact drives the SDK's real client
 * from an in-memory connection.
 *
 * Tests use the testing artifact rather than this API, which is why reaching it takes an explicit
 * `@OptIn(ConfigDirectorTestingApi::class)`.
 */
@RequiresOptIn(
    message = "This API is for the testing artifact maintained by ConfigDirector. Tests use " +
        "com.configdirector:android-sdk-testing instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@MustBeDocumented
public annotation class ConfigDirectorTestingApi
