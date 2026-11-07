package org.jscratch.sb3o.values

actual object JsNumbers {
    actual fun toString(value: Double): String = value.asDynamic().toString() as String

    actual fun toFixed(value: Double, digits: Int): String = value.asDynamic().toFixed(digits) as String

    actual fun parseRadix(digits: String, radix: Int): Double {
        val prefix =
            when (radix) {
                16 -> "0x"
                8 -> "0o"
                2 -> "0b"
                else -> throw IllegalArgumentException("radix $radix")
            }
        return js("Number")(prefix + digits) as Double
    }
}
