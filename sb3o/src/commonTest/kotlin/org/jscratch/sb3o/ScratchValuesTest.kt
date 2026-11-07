package org.jscratch.sb3o

import org.jscratch.sb3o.values.JsMath
import org.jscratch.sb3o.values.ScratchValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** scratch-vm's `Cast` and operator rules, which the folder computes with. */
class ScratchValuesTest {
    @Test
    fun parsesNumbersLikeJavaScript() {
        assertEquals(5.0, ScratchValues.parseNumber("0b101"))
        assertEquals(15.0, ScratchValues.parseNumber("0o17"))
        assertEquals(255.0, ScratchValues.parseNumber(" 0xFF "))
        assertEquals(1.206901553182527e24, ScratchValues.parseNumber("0xff9243a8f506b40928b5"))
        assertEquals(-1500.0, ScratchValues.parseNumber("-1.5e3"))
        assertEquals(0.5, ScratchValues.parseNumber(".5"))
        assertEquals(5.0, ScratchValues.parseNumber("5."))
        assertEquals(Double.NEGATIVE_INFINITY, ScratchValues.parseNumber("-Infinity"))
        assertEquals(0.0, ScratchValues.parseNumber(""))
        assertEquals(0.0, ScratchValues.parseNumber("  ﻿  "))
        val bad = listOf("0x-1", "-0x1", "0x", "0b2", "0x١", "0xａ", "1e", ".", "infinity", "1_000", "5\u001c", "1d")
        for (s in bad) assertTrue(ScratchValues.parseNumber(s).isNaN(), "'$s' should be NaN")
        // toNumber turns NaN into 0, like Cast.toNumber.
        assertEquals(0.0, ScratchValues.toNumber("abc"))
        assertEquals(0.0, ScratchValues.toNumber(Double.NaN))
        assertEquals(1.0, ScratchValues.toNumber(true))
    }

    @Test
    fun castsBooleansAndStrings() {
        assertFalse(ScratchValues.toBoolean(""))
        assertFalse(ScratchValues.toBoolean("0"))
        assertFalse(ScratchValues.toBoolean("FALSE"))
        assertTrue(ScratchValues.toBoolean("00"))
        assertTrue(ScratchValues.toBoolean(" "))
        assertFalse(ScratchValues.toBoolean(0.0))
        assertFalse(ScratchValues.toBoolean(Double.NaN))
        assertTrue(ScratchValues.toBoolean(-1.0))
        assertEquals("0.1", ScratchValues.toString(0.1))
        assertEquals("true", ScratchValues.toString(true))
        assertEquals("1e+21", ScratchValues.toString(1e21))
    }

    @Test
    fun comparesLikeScratch() {
        assertEquals(0.0, ScratchValues.compare("5", 5.0))
        assertEquals(0.0, ScratchValues.compare(" 5 ", "5.0"))
        assertEquals(0.0, ScratchValues.compare("abc", "ABC"))
        assertTrue(ScratchValues.compare("abc", "abd") < 0)
        assertTrue(ScratchValues.compare("10", "9") > 0)
        assertTrue(ScratchValues.compare("a10", "a9") < 0)
        // Empty and blank text count as text, so neither is equal to 0.
        assertTrue(ScratchValues.compare(" ", 0.0) != 0.0)
        assertTrue(ScratchValues.compare("", 0.0) != 0.0)
        assertEquals(0.0, ScratchValues.compare(true, 1.0))
        assertEquals(0.0, ScratchValues.compare("Infinity", Double.POSITIVE_INFINITY))
        assertTrue(ScratchValues.compare("NaN", 0.0) != 0.0)
    }

    @Test
    fun roundsAndDividesLikeJavaScript() {
        assertEquals(3.0, ScratchValues.jsRound(2.5))
        assertEquals(-2.0, ScratchValues.jsRound(-2.5))
        assertEquals(0.0, ScratchValues.jsRound(0.49999999999999994))
        assertEquals(1.0 / ScratchValues.jsRound(-0.4), Double.NEGATIVE_INFINITY)
        assertEquals(2.0, ScratchValues.mod(-7.0, 3.0))
        assertEquals(-1.0, ScratchValues.mod(5.0, -3.0))
        assertEquals(0.5, ScratchValues.mod(5.5, 1.0))
        assertTrue(ScratchValues.mod(5.0, 0.0).isNaN())
    }

    @Test
    fun mathopFollowsScratchVm() {
        assertEquals(0.5, ScratchValues.mathop("sin", 30.0))
        assertEquals(0.0, ScratchValues.mathop("sin", 180.0))
        assertEquals(1.0, ScratchValues.mathop("cos", 360.0))
        assertEquals(Double.POSITIVE_INFINITY, ScratchValues.mathop("tan", 90.0))
        assertEquals(Double.NEGATIVE_INFINITY, ScratchValues.mathop("tan", -90.0))
        assertEquals(1.0, ScratchValues.mathop("tan", 45.0))
        assertEquals(90.0, ScratchValues.mathop("asin", 1.0))
        assertEquals(45.0, ScratchValues.mathop("atan", 1.0))
        // Dividing by ln 10 is what scratch-vm does, rounding error included.
        assertEquals(2.9999999999999996, ScratchValues.mathop("log", 1000.0))
        assertEquals(100.0, ScratchValues.mathop("10 ^", 2.0))
        // V8 gives Math.E here; the JVM's StrictMath is a bit off, which is why the folder skips these there.
        if (JsMath.exact) assertEquals(2.718281828459045, ScratchValues.mathop("e ^", 1.0))
        assertEquals(4.0, ScratchValues.mathop("sqrt", 16.0))
        assertEquals(-2.0, ScratchValues.mathop("ceiling", -2.5))
        assertEquals(2.0, ScratchValues.mathop("ABS", -2.0))
        assertEquals(0.0, ScratchValues.mathop("nope", 7.0))
    }
}
