package com.configdirector.internal

import com.configdirector.ConfigDirectorValidationException
import com.configdirector.internal.telemetry.valueIdFor
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.util.Date
import java.util.TreeMap
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeValuesTest {

    @Test
    fun `a Boolean is a boolean config`() {
        val state = encodeNativeValue("k", true)

        assertThat(state.type).isEqualTo(ConfigType.BOOLEAN)
        assertThat(state.value).isEqualTo("true")
    }

    @Test
    fun `an Int and a Long are integer configs`() {
        assertThat(encodeNativeValue("k", 20).type).isEqualTo(ConfigType.INTEGER)
        assertThat(encodeNativeValue("k", 20).value).isEqualTo("20")
        assertThat(encodeNativeValue("k", -3_000_000_000L).type).isEqualTo(ConfigType.INTEGER)
        assertThat(encodeNativeValue("k", -3_000_000_000L).value).isEqualTo("-3000000000")
    }

    @Test
    fun `a Float and a Double are float configs written as their own type spells them`() {
        assertThat(encodeNativeValue("k", 2.5f).type).isEqualTo(ConfigType.FLOAT)
        assertThat(encodeNativeValue("k", 2.5f).value).isEqualTo("2.5")
        assertThat(encodeNativeValue("k", 0.1f).value).isEqualTo("0.1")
        assertThat(encodeNativeValue("k", 2.0).type).isEqualTo(ConfigType.FLOAT)
        assertThat(encodeNativeValue("k", 2.0).value).isEqualTo("2.0")
    }

    @Test
    fun `a float that Kotlin would write with an exponent is written in plain notation`() {
        assertThat(encodeNativeValue("k", 1e21).value).isEqualTo("1000000000000000000000")
        assertThat(encodeNativeValue("k", 1e-7).value).isEqualTo("0.0000001")
        assertThat(encodeNativeValue("k", -1.5e-8).value).isEqualTo("-0.000000015")
        assertThat(encodeNativeValue("k", 1e21f).value).isEqualTo("1000000000000000000000")
    }

    @Test
    fun `a String is a string config, even when it spells JSON`() {
        val state = encodeNativeValue("k", """{"a":1}""")

        assertThat(state.type).isEqualTo(ConfigType.STRING)
        assertThat(state.value).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `a Map with insertion order is a JSON config in that order`() {
        val state = encodeNativeValue(
            "k",
            mapOf("zebra" to 1, "apple" to listOf(1, 2.5, "x", null, true), "nested" to mapOf("b" to 2L, "a" to 1)),
        )

        assertThat(state.type).isEqualTo(ConfigType.JSON)
        assertThat(state.value)
            .isEqualTo("""{"zebra":1,"apple":[1,2.5,"x",null,true],"nested":{"b":2,"a":1}}""")
    }

    @Test
    fun `a Map without a defined order is written with sorted keys`() {
        assertThat(encodeNativeValue("k", HashMap(mapOf("zebra" to 1, "apple" to 2))).value)
            .isEqualTo("""{"apple":2,"zebra":1}""")
        assertThat(encodeNativeValue("k", java.util.Map.of("zebra", 1, "apple", 2)).value)
            .isEqualTo("""{"apple":2,"zebra":1}""")
    }

    @Test
    fun `a sorted Map keeps its order`() {
        val sorted = TreeMap<String, Any>(compareByDescending { it }).apply {
            put("apple", 1)
            put("zebra", 2)
        }

        assertThat(encodeNativeValue("k", sorted).value).isEqualTo("""{"zebra":2,"apple":1}""")
    }

    @Test
    fun `a List is a JSON config`() {
        assertThat(encodeNativeValue("k", listOf("x", 2, false)).value).isEqualTo("""["x",2,false]""")
    }

    @Test
    fun `JSON text escapes what JSON requires and keeps everything else`() {
        val state = encodeNativeValue("k", mapOf("g" to "quote\" back\\ nl\n tab\t ctrl\u0001 héllo ✓"))

        assertThat(state.value).isEqualTo("""{"g":"quote\" back\\ nl\n tab\t ctrl\u0001 héllo ✓"}""")
    }

    @Test
    fun `rejects a blank key`() {
        assertThrows(ConfigDirectorValidationException::class.java) { encodeNativeValue("", true) }
        assertThrows(ConfigDirectorValidationException::class.java) { encodeNativeValue("   ", true) }
    }

    @Test
    fun `rejects a Double that is not a number`() {
        assertThrows(ConfigDirectorValidationException::class.java) { encodeNativeValue("k", Double.NaN) }
    }

    @Test
    fun `rejects an infinite Double`() {
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `rejects a Float that is not a number`() {
        assertThrows(ConfigDirectorValidationException::class.java) { encodeNativeValue("k", Float.NaN) }
    }

    @Test
    fun `rejects an infinite Float`() {
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `rejects a Double inside a JSON document that is not finite`() {
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", mapOf("n" to Double.NaN))
        }
    }

    @Test
    fun `rejects a Float inside a JSON document that is not finite`() {
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", mapOf("n" to Float.NEGATIVE_INFINITY))
        }
    }

    @Test
    fun `rejects a Short`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", 3.toShort())
        }

        assertThat(failure).hasMessageThat().contains("java.lang.Short")
    }

    @Test
    fun `rejects a BigDecimal`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", BigDecimal.ONE)
        }

        assertThat(failure).hasMessageThat().contains("java.math.BigDecimal")
    }

    @Test
    fun `rejects a Char`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) { encodeNativeValue("k", 'c') }

        assertThat(failure).hasMessageThat().contains("java.lang.Character")
    }

    @Test
    fun `rejects a Date`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", Date(0))
        }

        assertThat(failure).hasMessageThat().contains("java.util.Date")
    }

    @Test
    fun `rejects a Set`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", setOf(1))
        }

        assertThat(failure).hasMessageThat().contains("Set")
    }

    @Test
    fun `rejects unsupported JSON contents`() {
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", mapOf("when" to Date(0)))
        }
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", listOf(setOf(1)))
        }
    }

    @Test
    fun `rejects a JSON object keyed by anything but Strings`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", mapOf(1 to "one"))
        }
        assertThat(failure).hasMessageThat().contains("String")
        assertThrows(ConfigDirectorValidationException::class.java) {
            encodeNativeValue("k", mapOf("outer" to mapOf(2 to "two")))
        }
    }

    @Test
    fun `identifies the value the way ConfigDirector does`() {
        val state = encodeNativeValue("k", mapOf("a" to 1))

        assertThat(state.valueId).isEqualTo(valueIdFor("""{"a":1}"""))
    }

    @Test
    fun `gives every config its own id and keeps its key`() {
        val first = encodeNativeValue("k", true)
        val second = encodeNativeValue("k", true)

        assertThat(first.key).isEqualTo("k")
        assertThat(first.id).isNotEmpty()
        assertThat(first.id).isNotEqualTo(second.id)
    }

    @Test
    fun `encodes every value of a map under its key`() {
        val encoded = encodeNativeValues(mapOf("flag" to true, "count" to 2))

        assertThat(encoded.keys).containsExactly("flag", "count").inOrder()
        assertThat(encoded.getValue("count").value).isEqualTo("2")
    }
}
