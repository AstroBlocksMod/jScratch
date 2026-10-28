package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastTest {
    @Test
    fun numbersPrintLikeJavaScript() {
        assertEquals("0", Cast.toString(0.0))
        assertEquals("0", Cast.toString(-0.0))
        assertEquals("42", Cast.toString(42.0))
        assertEquals("-3", Cast.toString(-3.0))
        assertEquals("0.1", Cast.toString(0.1))
        assertEquals("12345678.9", Cast.toString(12345678.9))
        assertEquals("100000000000000000000", Cast.toString(1e20))
        assertEquals("1e+21", Cast.toString(1e21))
        assertEquals("1e-7", Cast.toString(1e-7))
        assertEquals("1.5e-7", Cast.toString(1.5e-7))
        assertEquals("Infinity", Cast.toString(Double.POSITIVE_INFINITY))
        assertEquals("NaN", Cast.toString(Double.NaN))
    }

    @Test
    fun stringsConvertToNumbersLikeJavaScript() {
        assertEquals(0.0, Cast.toNumber(""))
        assertEquals(0.0, Cast.toNumber("   "))
        assertEquals(12.0, Cast.toNumber(" 12 "))
        assertEquals(-1.5e3, Cast.toNumber("-1.5e3"))
        assertEquals(16.0, Cast.toNumber("0x10"))
        assertEquals(0.0, Cast.toNumber("abc"))
        assertEquals(0.0, Cast.toNumber("1,5"))
        assertEquals(Double.POSITIVE_INFINITY, Cast.toNumber("Infinity"))
        assertEquals(1.0, Cast.toNumber(true))
    }

    @Test
    fun parseNumberFollowsTheNumberGrammar() {
        assertEquals(100000.0, Cast.parseNumber("1e5"))
        assertEquals(120.0, Cast.parseNumber("12e+1"))
        assertEquals(1.2, Cast.parseNumber("12e-1"))
        assertEquals(12.0, Cast.parseNumber("12."))
        assertEquals(0.5, Cast.parseNumber(".5"))
        assertEquals(-0.5, Cast.parseNumber("-.5"))
        assertEquals(3.0, Cast.parseNumber("+3"))
        assertEquals(123456789012345.0, Cast.parseNumber("123456789012345"))
        assertEquals(1234567890123456789.0, Cast.parseNumber("1234567890123456789"))
        assertEquals(0.1, Cast.parseNumber("0.1"))
        assertEquals(255.0, Cast.parseNumber("0xff"))
        assertEquals(255.0, Cast.parseNumber("0XFF"))
        assertEquals(15.0, Cast.parseNumber("0o17"))
        assertEquals(5.0, Cast.parseNumber("0b101"))
        assertEquals(1.2089258196146292e24, Cast.parseNumber("0x" + "ff".repeat(10)))
        // JavaScript rounds the exact value once; rounding after every digit would give 1.2069015531825268e24.
        assertEquals(1.206901553182527e24, Cast.parseNumber("0xff9243a8f506b40928b5"))
        assertEquals(1.1805916207174113e21, Cast.parseNumber("0b" + "1".repeat(70)))
        assertEquals(Double.POSITIVE_INFINITY, Cast.parseNumber("+Infinity"))
        assertEquals(Double.NEGATIVE_INFINITY, Cast.parseNumber("-Infinity"))
        assertEquals(Double.POSITIVE_INFINITY, Cast.parseNumber("1e400"))
        for (
        bad in listOf(
            ".", "+", "-", "e5", "1e", "1e+", "1x", "0x", "0xg1", "0b12", "0o8", "+0x10", "-0b1",
            "1 2", "12d", "0x10p1", "Infinity5", "1_000", "NaN", "infinity",
            // Only ASCII digits count, as in JavaScript: Arabic-Indic one, fullwidth a and 1.
            "0x١", "0xａ", "0b１", "١",
        )
        ) {
            assertTrue(Cast.parseNumber(bad).isNaN(), "'$bad' should be NaN")
        }
    }

    @Test
    fun booleans() {
        assertFalse(Cast.toBoolean(""))
        assertFalse(Cast.toBoolean("0"))
        assertFalse(Cast.toBoolean("FALSE"))
        assertTrue(Cast.toBoolean("hello"))
        assertFalse(Cast.toBoolean(0.0))
        assertTrue(Cast.toBoolean(2.0))
    }

    @Test
    fun comparisonsFollowScratch() {
        assertEquals(0.0, Cast.compare("10", 10.0))
        assertTrue(Cast.compare("9", "10") < 0)
        assertTrue(Cast.compare("abc", "ABD") < 0)
        assertEquals(0.0, Cast.compare("Hello", "hello"))
        assertTrue(Cast.compare("", 0.0) != 0.0)
        assertEquals(0.0, Cast.compare(Double.POSITIVE_INFINITY, "Infinity"))
    }

    @Test
    fun listIndices() {
        assertEquals(3, Cast.toListIndex("last", 3, false))
        assertEquals(Cast.LIST_INVALID, Cast.toListIndex("last", 0, false))
        assertEquals(Cast.LIST_ALL, Cast.toListIndex("all", 3, true))
        assertEquals(Cast.LIST_INVALID, Cast.toListIndex("all", 3, false))
        assertEquals(2, Cast.toListIndex(2.7, 3, false))
        assertEquals(Cast.LIST_INVALID, Cast.toListIndex(4.0, 3, false))
        assertEquals(Cast.LIST_INVALID, Cast.toListIndex(0.0, 3, false))
    }

    @Test
    fun colors() {
        assertEquals(0xFFFF8800.toInt(), Cast.toArgb("#ff8800"))
        assertEquals(0xFF112233.toInt(), Cast.toArgb(0x112233.toDouble()))
    }
}
