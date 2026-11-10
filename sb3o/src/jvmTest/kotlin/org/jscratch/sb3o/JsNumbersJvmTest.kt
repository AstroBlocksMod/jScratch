package org.jscratch.sb3o

import org.jscratch.sb3o.values.JsNumbers
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The digit search in JsNumbers must not depend on Double.toString, which TeaVM implements its own way.
 * On the JDK, whose Double.toString has printed the shortest digits since 19, starting the search from a
 * bad guess has to land on the same digits as starting from the JDK's.
 */
class JsNumbersJvmTest {
    @Test
    fun theSearchFindsTheShortestDigitsFromAnyGuess() {
        val random = Random(20261109)
        repeat(50_000) {
            val v = Math.abs(java.lang.Double.longBitsToDouble(random.nextLong()))
            if (!v.isFinite() || v == 0.0) return@repeat
            val fromJdk = JsNumbers.shortest(v)
            for (guess in intArrayOf(1, 9, 17)) assertEquals(fromJdk, JsNumbers.shortest(v, guess), "$v from $guess")
            // The digits read back as the value.
            assertEquals(v, fromJdk.toDouble(), "$v")
            // And the JDK agrees on how many there are, except that it never prints fewer than two.
            val jdk = BigDecimal(java.lang.Double.toString(v)).stripTrailingZeros()
            if (jdk.precision() > 1) assertEquals(jdk.precision(), fromJdk.precision(), "$v")
        }
    }
}
