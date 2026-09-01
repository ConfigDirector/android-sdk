package com.configdirector.internal.telemetry

import java.security.MessageDigest

/**
 * Identifies a config value by a digest of its text, so that the same value reported by two SDKs is
 * counted once. Every SDK has to spell these identically: the same bytes of digest, the same base62
 * alphabet, the same zero padding.
 */
internal object ValueIds {

    /** The base62 digits the leading 128 bits of the digest produce: ceil(128 / log2(62)). */
    private const val LENGTH = 22
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val RADIX = 62UL
    private const val HALF = 32

    fun generate(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return base62(bigEndian(digest, 0), bigEndian(digest, Long.SIZE_BYTES))
    }

    private fun bigEndian(digest: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (index in 0 until Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (digest[offset + index].toULong() and 0xFFUL)
        }
        return value
    }

    // Hand-rolled because BigInteger stops at radix 36, and because the encoding is fixed-width and
    // zero-padded rather than dropping leading zeros. Unsigned longs rather than BigInteger: 62^22
    // covers 128 bits, so the width is known up front and no digit needs an allocation.
    private fun base62(high: ULong, low: ULong): String {
        val digits = CharArray(LENGTH)
        var remainingHigh = high
        var remainingLow = low

        for (index in LENGTH - 1 downTo 0) {
            // Long division by RADIX over 128 bits, a 32-bit limb at a time.
            val quotientHigh = remainingHigh / RADIX
            var carry = remainingHigh % RADIX

            val upper = (carry shl HALF) or (remainingLow shr HALF)
            val quotientUpper = upper / RADIX
            carry = upper % RADIX

            val lower = (carry shl HALF) or (remainingLow and 0xFFFFFFFFUL)
            val quotientLower = lower / RADIX
            carry = lower % RADIX

            digits[index] = ALPHABET[carry.toInt()]
            remainingHigh = quotientHigh
            remainingLow = (quotientUpper shl HALF) or quotientLower
        }
        return String(digits)
    }
}
