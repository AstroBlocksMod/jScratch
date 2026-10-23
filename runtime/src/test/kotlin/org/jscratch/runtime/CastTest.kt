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
