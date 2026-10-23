package org.jscratch.runtime

/**
 * Operator blocks that are not plain JVM arithmetic. Everything here is a pure
 * function of its arguments and mirrors scratch-vm's `scratch3_operators.js`.
 */
object Ops {
    @JvmStatic
    fun mod(n: Double, modulus: Double): Double {
        var result = n % modulus
        // JavaScript keeps the sign of the dividend, Scratch wants the sign of the divisor.
        if (result / modulus < 0) result += modulus
        return result
    }

    @JvmStatic
    fun round(n: Double): Double {
        if (n.isNaN() || n.isInfinite()) return n
        return Math.floor(n + 0.5)
    }

    @JvmStatic
    fun random(from: Any?, to: Any?): Double {
        val nFrom = Cast.toNumber(from)
        val nTo = Cast.toNumber(to)
        val low = if (nFrom <= nTo) nFrom else nTo
        val high = if (nFrom <= nTo) nTo else nFrom
        if (low == high) return low
        if (Cast.isInt(from) && Cast.isInt(to)) {
            return low + Math.floor(Math.random() * (high - low + 1))
        }
        return Math.random() * (high - low) + low
    }

    @JvmStatic
    fun join(a: Any?, b: Any?): String = Cast.toString(a) + Cast.toString(b)

    @JvmStatic
    fun letterOf(letter: Any?, string: Any?): String {
        val index = Cast.toNumber(letter).toInt() - 1
        val str = Cast.toString(string)
        if (index < 0 || index >= str.length) return ""
        return str[index].toString()
    }

    @JvmStatic
    fun length(string: Any?): Double = Cast.toString(string).length.toDouble()

    @JvmStatic
    fun contains(string: Any?, substring: Any?): Boolean =
        Cast.toString(string).lowercase().contains(Cast.toString(substring).lowercase())

    @JvmStatic
    fun mathop(operator: String, n: Double): Double = when (operator) {
        "abs" -> Math.abs(n)
        "floor" -> Math.floor(n)
        "ceiling" -> Math.ceil(n)
        "sqrt" -> Math.sqrt(n)
        "sin" -> round10(Math.sin(Math.toRadians(n)))
        "cos" -> round10(Math.cos(Math.toRadians(n)))
        "tan" -> tan(n)
        "asin" -> Math.toDegrees(Math.asin(n))
        "acos" -> Math.toDegrees(Math.acos(n))
        "atan" -> Math.toDegrees(Math.atan(n))
        "ln" -> Math.log(n)
        "log" -> Math.log10(n)
        "e ^" -> Math.exp(n)
        "10 ^" -> Math.pow(10.0, n)
        else -> 0.0
    }

    private fun round10(v: Double): Double = Math.round(v * 1e10) / 1e10

    private fun tan(angle: Double): Double {
        val a = angle % 360.0
        return when {
            a == -270.0 || a == 90.0 -> Double.POSITIVE_INFINITY
            a == 270.0 || a == -90.0 -> Double.NEGATIVE_INFINITY
            else -> round10(Math.tan(Math.toRadians(a)))
        }
    }

    /** `repeat` loop count: round and clamp into an int. */
    @JvmStatic
    fun repeatCount(n: Double): Int {
        val r = round(n)
        return when {
            r.isNaN() -> 0
            r >= Int.MAX_VALUE -> Int.MAX_VALUE
            r <= 0 -> 0
            else -> r.toInt()
        }
    }

    /** Adds two Scratch values as numbers and boxes the result (used by `change variable by`). */
    @JvmStatic
    fun add(a: Any?, b: Double): Any = Cast.toNumber(a) + b

    @JvmStatic
    fun box(d: Double): Any = d

    @JvmStatic
    fun box(b: Boolean): Any = b
}
