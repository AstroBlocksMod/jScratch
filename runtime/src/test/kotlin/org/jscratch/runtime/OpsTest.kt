package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpsTest {
    @Test
    fun modFollowsTheDivisorSign() {
        assertEquals(1.0, Ops.mod(7.0, 3.0))
        assertEquals(2.0, Ops.mod(-7.0, 3.0))
        assertEquals(-2.0, Ops.mod(7.0, -3.0))
        assertEquals(0.5, Ops.mod(5.5, 1.0))
    }

    @Test
    fun roundHalfUp() {
        assertEquals(3.0, Ops.round(2.5))
        assertEquals(-2.0, Ops.round(-2.5))
        assertEquals(4.0, Ops.round(3.7))
    }

    @Test
    fun strings() {
        assertEquals("ab", Ops.join("a", "b"))
        assertEquals("a1", Ops.join("a", 1.0))
        assertEquals("y", Ops.letterOf(2.0, "xyz"))
        assertEquals("", Ops.letterOf(9.0, "xyz"))
        assertEquals(5.0, Ops.length("hello"))
        assertTrue(Ops.contains("Hello World", "WORLD"))
    }

    @Test
    fun mathops() {
        assertEquals(1.0, Ops.mathop("sin", 90.0))
        assertEquals(0.0, Ops.mathop("cos", 90.0))
        assertEquals(Double.POSITIVE_INFINITY, Ops.mathop("tan", 90.0))
        assertEquals(3.0, Ops.mathop("sqrt", 9.0))
        assertEquals(2.0, Ops.mathop("log", 100.0))
        assertEquals(1000.0, Ops.mathop("10 ^", 3.0))
    }

    @Test
    fun randomRespectsIntegerArguments() {
        repeat(50) {
            val r = Ops.random("1", "3")
            assertTrue(r == Math.floor(r) && r in 1.0..3.0)
        }
        assertEquals(4.0, Ops.random(4.0, 4.0))
    }

    @Test
    fun repeatCounts() {
        assertEquals(3, Ops.repeatCount(2.6))
        assertEquals(0, Ops.repeatCount(-1.0))
        assertEquals(0, Ops.repeatCount(Double.NaN))
    }
}
