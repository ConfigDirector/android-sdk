package com.configdirector

/**
 * Metadata about your application, which targeting rules can be written against.
 *
 * Each field left null is filled in with what the running application reports.
 */
public class Metadata @JvmOverloads constructor(
    /** Your application's name. Defaults to the label the application manifest declares. */
    public val appName: String? = null,
    /** Your application's version. Defaults to the version name the package reports. */
    public val appVersion: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Metadata && appName == other.appName && appVersion == other.appVersion)

    override fun hashCode(): Int = 31 * appName.hashCode() + appVersion.hashCode()

    override fun toString(): String = "Metadata(appName=$appName, appVersion=$appVersion)"

    public companion object {
        private val EMPTY = Metadata()

        /** Metadata with neither field set, leaving both to be filled in from the application. */
        @JvmStatic
        public fun empty(): Metadata = EMPTY
    }
}
