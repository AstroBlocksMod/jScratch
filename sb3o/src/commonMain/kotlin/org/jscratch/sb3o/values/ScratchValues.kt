package org.jscratch.sb3o.values

import kotlin.math.abs
import kotlin.math.floor

/**
 * Scratch's value semantics, as scratch-vm's `Cast` defines them. A Scratch value is a Double,
 * a String or a Boolean, and every block converts its inputs on use. The optimizer computes with
 * these when it folds blocks, so a folded value is exactly what the blocks would have computed.
 */
object ScratchValues {
    /** Scratch `toNumber`: JavaScript `Number()`, with NaN turned into 0. */
    fun toNumber(value: Any?): Double {
        val n = toNumberOrNaN(value)
        return if (n.isNaN()) 0.0 else n
    }

    /** JavaScript `Number(value)`, NaN included. */
    fun toNumberOrNaN(value: Any?): Double = when (value) {
        null -> 0.0
        is Double -> value
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> parseNumber(value)
        else -> Double.NaN
    }

    /**
     * JavaScript `Number(string)`: the string is trimmed of JavaScript's whitespace, and then has to be
     * `[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?`, `[+-]?Infinity`, or an unsigned `0x`, `0o` or `0b`
     * integer of ASCII digits (rounded once, however many digits). Anything else is NaN, and an empty
     * string is 0.
     */
    fun parseNumber(raw: String): Double {
        val s = jsTrim(raw)
        val n = s.length
        if (n == 0) return 0.0
        var i = 0
        val first = s[0]
        val negative = first == '-'
        if (negative || first == '+') i = 1
        if (i == n) return Double.NaN
        when (s[i]) {
            'I' -> {
                return when {
                    s.regionMatches(i, "Infinity", 0, 8) && i + 8 == n ->
                        if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                    else -> Double.NaN
                }
            }
            '0' -> {
                // The prefixed integer forms take no sign, like JavaScript.
                if (i == 0 && n > 2) {
                    val radix =
                        when (s[1]) {
                            'x', 'X' -> 16
                            'o', 'O' -> 8
                            'b', 'B' -> 2
                            else -> 0
                        }
                    if (radix != 0) return parsePrefixed(s, radix)
                }
            }
        }
        var digits = 0
        while (i < n && s[i] in '0'..'9') {
            digits++
            i++
        }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') {
                digits++
                i++
            }
        }
        if (digits == 0) return Double.NaN
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            if (i == n || s[i] !in '0'..'9') return Double.NaN
            while (i < n && s[i] in '0'..'9') i++
        }
        if (i != n) return Double.NaN
        return s.toDouble()
    }

    private fun parsePrefixed(s: String, radix: Int): Double {
        for (i in 2 until s.length) {
            val c = s[i]
            val digit =
                when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'z' -> c - 'a' + 10
                    in 'A'..'Z' -> c - 'A' + 10
                    else -> return Double.NaN
                }
            if (digit >= radix) return Double.NaN
        }
        return JsNumbers.parseRadix(s.substring(2), radix)
    }

    /** Whether [raw], trimmed, is a number in decimal notation, the only kind JSON can store as a number. */
    fun isDecimal(raw: String): Boolean {
        val s = jsTrim(raw)
        val n = s.length
        var i = if (n > 0 && (s[0] == '+' || s[0] == '-')) 1 else 0
        var digits = 0
        while (i < n && s[i] in '0'..'9') {
            digits++
            i++
        }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') {
                digits++
                i++
            }
        }
        if (digits == 0) return false
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            if (i == n || s[i] !in '0'..'9') return false
            while (i < n && s[i] in '0'..'9') i++
        }
        return i == n
    }

    fun toBoolean(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> !(value.isEmpty() || value == "0" || value.lowercase() == "false")
        is Double -> value != 0.0 && !value.isNaN()
        is Number -> value.toDouble() != 0.0
        else -> true
    }

    /** JavaScript `String(value)`. */
    fun toString(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Double -> JsNumbers.toString(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> JsNumbers.toString(value.toDouble())
        else -> value.toString()
    }

    /** `Cast.isWhiteSpace`: a string that is empty or all JavaScript whitespace. */
    fun isWhiteSpace(value: Any?): Boolean = value is String && jsTrim(value).isEmpty()

    /**
     * `Cast.compare`: numeric when both sides look like numbers, otherwise a case-insensitive comparison
     * of the texts. Negative, zero or positive.
     */
    fun compare(a: Any?, b: Any?): Double {
        var n1 = toNumberOrNaN(a)
        var n2 = toNumberOrNaN(b)
        if (n1 == 0.0 && isWhiteSpace(a)) {
            n1 = Double.NaN
        } else if (n2 == 0.0 && isWhiteSpace(b)) {
            n2 = Double.NaN
        }
        if (n1.isNaN() || n2.isNaN()) {
            val s1 = toString(a).lowercase()
            val s2 = toString(b).lowercase()
            return when {
                s1 < s2 -> -1.0
                s1 > s2 -> 1.0
                else -> 0.0
            }
        }
        if (n1.isInfinite() && n2.isInfinite() && (n1 > 0) == (n2 > 0)) return 0.0
        return n1 - n2
    }

    /** JavaScript `Math.round`: halves round toward positive infinity, and -0.4 rounds to -0. */
    fun jsRound(x: Double): Double {
        if (!x.isFinite()) return x
        if (x < 0 && x >= -0.5) return -0.0
        val f = floor(x)
        return if (x - f >= 0.5) f + 1 else f
    }

    /** Scratch `mod`: the remainder takes the sign of the divisor, as with floored division. */
    fun mod(n: Double, modulus: Double): Double {
        var result = n % modulus
        if (result / modulus < 0) result += modulus
        return result
    }

    /** The `mathop` block. [operator] is the menu text; an unknown one gives 0. */
    fun mathop(operator: String, n: Double): Double = when (operator.lowercase()) {
        "abs" -> abs(n)
        "floor" -> floor(n)
        "ceiling" -> kotlin.math.ceil(n)
        "sqrt" -> kotlin.math.sqrt(n)
        "sin" -> roundTo10(JsMath.sin((PI * n) / 180))
        "cos" -> roundTo10(JsMath.cos((PI * n) / 180))
        "tan" -> tan(n)
        "asin" -> (JsMath.asin(n) * 180) / PI
        "acos" -> (JsMath.acos(n) * 180) / PI
        "atan" -> (JsMath.atan(n) * 180) / PI
        "ln" -> JsMath.log(n)
        "log" -> JsMath.log(n) / LN10
        "e ^" -> JsMath.exp(n)
        "10 ^" -> JsMath.pow(10.0, n)
        else -> 0.0
    }

    /** scratch-vm's `MathUtil.tan`: exact at the poles, otherwise rounded to 10 decimals. */
    private fun tan(angle: Double): Double {
        val a = angle % 360
        return when (a) {
            -270.0, 90.0 -> Double.POSITIVE_INFINITY
            -90.0, 270.0 -> Double.NEGATIVE_INFINITY
            else -> roundTo10(JsMath.tan((PI * a) / 180))
        }
    }

    /** `parseFloat(x.toFixed(10))`, which scratch-vm applies to sin, cos and tan to hide floating point noise. */
    private fun roundTo10(x: Double): Double {
        if (!x.isFinite()) return x
        return JsNumbers.toFixed(x, 10).toDouble()
    }

    /** `Math.PI` and `Math.LN10`. */
    const val PI = 3.141592653589793
    const val LN10 = 2.302585092994046

    /**
     * JavaScript's `String.prototype.trim`: it strips the Unicode space separators, the line terminators
     * and the byte order mark, but not the control characters below space that Kotlin's `trim` strips.
     */
    fun jsTrim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isJsWhitespace(s[start])) start++
        while (end > start && isJsWhitespace(s[end - 1])) end--
        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }

    private fun isJsWhitespace(c: Char): Boolean = when (c) {
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', '　', '﻿',
        -> true
        else -> c in ' '..' '
    }
}
