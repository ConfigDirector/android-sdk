package com.configdirector

import com.configdirector.internal.Constants

/**
 * The name and version a ConfigDirector wrapper reports to the server in place of this SDK's own,
 * so that the server's SDK usage tells the wrapper apart from applications on the SDK directly.
 *
 * Only wrappers maintained by ConfigDirector are represented, which is why there is a factory per
 * wrapper taking its version and no way to build one from an arbitrary name. Hand it to the
 * [ConfigDirectorClient] constructor that takes one.
 */
@ConfigDirectorWrapperApi
public class SdkIdentity private constructor(
    /** What the wrapper is called in the server's SDK usage. */
    public val name: String,

    /** The version the wrapper is published under. */
    public val version: String,
) {

    override fun toString(): String = "$name/$version"

    public companion object {
        @get:JvmSynthetic
        internal val ANDROID_CLIENT_SDK: SdkIdentity =
            SdkIdentity(Constants.SDK_NAME, Constants.SDK_VERSION)

        /**
         * The ConfigDirector OpenFeature provider for Android, published as [version].
         *
         * @throws ConfigDirectorValidationException if [version] is blank
         */
        @JvmStatic
        public fun openFeatureProvider(version: String): SdkIdentity {
            if (version.isBlank()) {
                throw ConfigDirectorValidationException(
                    "No version was provided for the OpenFeature provider. The identity it " +
                        "reports to the server cannot be built without one.",
                )
            }
            return SdkIdentity(OPENFEATURE_PROVIDER_NAME, version)
        }

        private const val OPENFEATURE_PROVIDER_NAME = "android-openfeature-client-provider"
    }
}
