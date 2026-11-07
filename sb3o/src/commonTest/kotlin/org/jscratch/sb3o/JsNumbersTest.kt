package org.jscratch.sb3o

import org.jscratch.sb3o.values.JsNumbers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM has to print numbers exactly as JavaScript does; on JavaScript these are the engine's
 * own answers, so the same test pins both platforms to the same strings.
 */
class JsNumbersTest {
    @Test
    fun formatsLikeJavaScript() {
        val cases =
            mapOf(
                0.0 to "0",
                -0.0 to "0",
                5.0 to "5",
                -42.0 to "-42",
                0.1 to "0.1",
                1.0 / 3 to "0.3333333333333333",
                0.1 + 0.2 to "0.30000000000000004",
                1e15 to "1000000000000000",
                1e20 to "100000000000000000000",
                1e21 to "1e+21",
                1.5e21 to "1.5e+21",
                123456789012345680000.0 to "123456789012345680000",
                1e-6 to "0.000001",
                1e-7 to "1e-7",
                1.5e-7 to "1.5e-7",
                -2.5e-10 to "-2.5e-10",
                Double.MAX_VALUE to "1.7976931348623157e+308",
                Double.MIN_VALUE to "5e-324",
                1e-323 to "1e-323",
                2.5e-323 to "2.5e-323",
                25.0 to "25",
                1.5 to "1.5",
                -0.000001234 to "-0.000001234",
                123.456e10 to "1234560000000",
                Double.NaN to "NaN",
                Double.POSITIVE_INFINITY to "Infinity",
                Double.NEGATIVE_INFINITY to "-Infinity",
            )
        for ((n, s) in cases) assertEquals(s, JsNumbers.toString(n), "toString($n)")
    }

    @Test
    fun toFixedRoundsTheExactValue() {
        assertEquals("0.33", JsNumbers.toFixed(1.0 / 3, 2))
        assertEquals("2.50", JsNumbers.toFixed(2.5, 2))
        assertEquals("3", JsNumbers.toFixed(2.5, 0))
        assertEquals("-3", JsNumbers.toFixed(-2.5, 0))
        // 1.005 is really 1.00499999999999989..., so it rounds down, unlike decimal rounding would.
        assertEquals("1.00", JsNumbers.toFixed(1.005, 2))
        assertEquals("1.01", JsNumbers.toFixed(1.015, 2))
        assertEquals("-0.00", JsNumbers.toFixed(-0.001, 2))
        assertEquals("0.00", JsNumbers.toFixed(-0.0, 2))
        assertEquals("0.0000000000", JsNumbers.toFixed(1.2246467991473532e-16, 10))
        assertEquals("1e+21", JsNumbers.toFixed(1e21, 2))
        assertEquals("NaN", JsNumbers.toFixed(Double.NaN, 2))
    }

    @Test
    fun parsesRadixDigitsRoundedOnce() {
        assertEquals(255.0, JsNumbers.parseRadix("ff", 16))
        assertEquals(15.0, JsNumbers.parseRadix("17", 8))
        assertEquals(5.0, JsNumbers.parseRadix("101", 2))
        assertEquals(1.206901553182527e24, JsNumbers.parseRadix("ff9243a8f506b40928b5", 16))
    }
}
