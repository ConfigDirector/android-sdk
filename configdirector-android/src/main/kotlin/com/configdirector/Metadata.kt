package com.configdirector

/**
 * Metadata about your application, which targeting rules can be written against.
 *
 * A field left null is left out of what the SDK sends.
 */
public class Metadata @JvmOverloads constructor(
    /** Your application's name. */
    public val appName: String? = null,
    /** Your application's version. */
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
