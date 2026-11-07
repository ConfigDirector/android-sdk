package com.configdirector

/** One of the values a config can serve, such as the control or a treatment of an experiment. */
public class Variation @JvmOverloads constructor(
    /** The ID ConfigDirector assigned to the variation, which stays the same when it is renamed. */
    public val id: String,

    /** The variation's name, or null when it has none. */
    public val name: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Variation && id == other.id && name == other.name)

    override fun hashCode(): Int = 31 * id.hashCode() + name.hashCode()

    override fun toString(): String = "Variation(id=$id, name=$name)"
}
