package org.jscratch.sb3o.values

/**
 * The parts of JavaScript's number handling that Scratch's behavior depends on and that differ
 * between platforms. On JavaScript they are the engine's own; on the JVM they are reproduced
 * exactly, so a value folded on either platform reads the same as Scratch would print it.
 */
expect object JsNumbers {
    /** `Number.prototype.toString()`: shortest round-trip digits, exponent notation outside 1e-7 to 1e21. */
    fun toString(value: Double): String

    /** `Number.prototype.toFixed(digits)`: decimal rounding of the exact binary value, ties away from zero. */
    fun toFixed(value: Double, digits: Int): String

    /**
     * The value of an unsigned integer written in [radix] (2, 8 or 16) with [digits] of that radix,
     * rounded to a double once, as `Number("0x...")` does. The digits must already be valid.
     */
    fun parseRadix(digits: String, radix: Int): Double
}
