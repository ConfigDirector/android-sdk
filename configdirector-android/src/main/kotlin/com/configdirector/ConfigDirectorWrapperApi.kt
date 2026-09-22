package com.configdirector

/**
 * Marks the API through which a wrapper maintained by ConfigDirector, such as the OpenFeature
 * provider, reports its own name and version to the server in place of this SDK's.
 *
 * An application has no use for it, which is why reaching it takes an explicit
 * `@OptIn(ConfigDirectorWrapperApi::class)`.
 */
@RequiresOptIn(
    message = "This API is for wrappers maintained by ConfigDirector, and is not meant for " +
        "applications.",
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
public annotation class ConfigDirectorWrapperApi
