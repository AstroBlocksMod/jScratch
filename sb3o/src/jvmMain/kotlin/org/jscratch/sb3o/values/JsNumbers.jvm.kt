package org.jscratch.sb3o.values

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

actual object JsNumbers {
    /**
     * `Number::toString`: the fewest significant digits that read back as [value], the closest such digits
     * when there is a choice, laid out plain up to 1e21, as a decimal down to 1e-6 and in exponent notation
     * beyond. The digits come from [shortest], which works them out with exact arithmetic. `Double.toString`
     * only gives the starting guess: the JDK has printed the shortest digits since 19, but always at least
     * two (4.9E-324 where JavaScript says 5e-324), and TeaVM, which translates this file for the
     * playground, does not promise the shortest at all.
     */
    actual fun toString(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"
        // Whole numbers that a long holds exactly, the common case, need no search.
        if (Math.abs(value) < 9.007199254740992E15 && value == Math.rint(value)) return value.toLong().toString()
        val bd = shortest(Math.abs(value))
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        // The value is 0.<digits> times 10^n.
        val n = k - bd.scale()
        val sb = StringBuilder()
        if (value < 0) sb.append('-')
        when {
            n in k..21 -> sb.append(digits).append("0".repeat(n - k))
            n in 1..21 -> sb.append(digits, 0, n).append('.').append(digits, n, k)
            n in -5..0 -> sb.append("0.").append("0".repeat(-n)).append(digits)
            else -> {
                val e = n - 1
                sb.append(digits[0])
                if (k > 1) sb.append('.').append(digits, 1, k)
                sb.append('e').append(if (e < 0) '-' else '+').append(Math.abs(e))
            }
        }
        return sb.toString()
    }

    /**
     * The shortest decimal that reads back as [value] (positive and finite), with trailing zeros stripped.
     * A decimal reads back as [value] when it lies between the midpoints to the neighboring doubles, on a
     * midpoint only when [value]'s mantissa is even, since reading rounds ties to even. If some decimal of
     * p digits does, so does the one of p digits just below or just above the exact value, whichever is
     * closer, and so does one of p + 1 digits; so the search can step down from a guess that works, or up
     * from one that does not. [guess] is the number of digits to start from, by default as many as
     * `Double.toString` printed.
     */
    internal fun shortest(value: Double, guess: Int = guessDigits(value)): BigDecimal {
        val exact = BigDecimal(value)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        val evenMantissa = bits and 1L == 0L
        // Below the smallest double is zero, and above the largest the gap is the same as below it.
        val below = BigDecimal(java.lang.Double.longBitsToDouble(bits - 1))
        val next = java.lang.Double.longBitsToDouble(bits + 1)
        val gapAbove = if (next.isInfinite()) exact.subtract(below) else BigDecimal(next).subtract(exact)
        val low = exact.subtract(exact.subtract(below).multiply(HALF))
        val high = exact.add(gapAbove.multiply(HALF))

        fun readsBack(c: BigDecimal): Boolean {
            val lo = c.compareTo(low)
            val hi = c.compareTo(high)
            return (lo > 0 || (lo == 0 && evenMantissa)) && (hi < 0 || (hi == 0 && evenMantissa))
        }

        fun best(p: Int): BigDecimal? {
            val down = exact.round(MathContext(p, RoundingMode.DOWN))
            val up = exact.round(MathContext(p, RoundingMode.UP))
            val okDown = readsBack(down)
            val okUp = readsBack(up)
            if (!okDown) return if (okUp) up else null
            if (!okUp) return down
            val d = exact.subtract(down).compareTo(up.subtract(exact))
            return when {
                d < 0 -> down
                d > 0 -> up
                // Equally close: the specification takes the even one.
                else -> if (down.unscaledValue().testBit(0)) up else down
            }
        }

        var p = guess.coerceIn(1, 17)
        var found = best(p)
        while (found == null && p < 17) found = best(++p)
        if (found == null) return exact.round(MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        while (p > 1) {
            found = best(p - 1) ?: break
            p--
        }
        return found!!.stripTrailingZeros()
    }

    private fun guessDigits(value: Double): Int {
        val text = java.lang.Double.toString(value)
        val mantissa = text.substringBefore('E').trimStart('0', '.').replace(".", "").trimEnd('0')
        return mantissa.length
    }

    private val HALF = BigDecimal("0.5")

    /**
     * The specification rounds the exact value of the double to [digits] decimals and, on a tie,
     * picks the larger magnitude; the sign is handled apart, so -0.001 gives "-0.00". Values of
     * 1e21 and beyond are printed as by [toString].
     */
    actual fun toFixed(value: Double, digits: Int): String {
        if (!value.isFinite() || Math.abs(value) >= 1e21) return toString(value)
        val negative = value < 0
        val text = BigDecimal(Math.abs(value)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
        return if (negative) "-$text" else text
    }

    actual fun parseRadix(digits: String, radix: Int): Double = BigInteger(digits, radix).toDouble()
}
