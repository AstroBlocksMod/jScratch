package org.jscratch.sb3o.values

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

actual object JsNumbers {
    /**
     * `Double.toString` gives the shortest digits that round-trip since JDK 19, like JavaScript, with
     * one difference: Java prints at least two significant digits (4.9E-324 for the smallest double)
     * where JavaScript prints one when one is enough (5e-324). The digits are then laid out by the
     * rules of `Number::toString`: plain up to 1e21, decimal down to 1e-6, exponent notation beyond.
     */
    actual fun toString(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"
        var bd = BigDecimal(java.lang.Double.toString(value)).stripTrailingZeros()
        if (bd.precision() == 2) {
            val one = bd.round(MathContext(1, RoundingMode.HALF_EVEN))
            if (one.toDouble() == value) bd = one.stripTrailingZeros()
        }
        val digits = bd.unscaledValue().abs().toString()
        val k = digits.length
        // The value is 0.<digits> times 10^n.
        val n = k - bd.scale()
        val sb = StringBuilder()
        if (bd.signum() < 0) sb.append('-')
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
