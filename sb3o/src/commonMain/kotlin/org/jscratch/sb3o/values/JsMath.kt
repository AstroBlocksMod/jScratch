package org.jscratch.sb3o.values

/**
 * The transcendental functions of JavaScript's `Math`. On JavaScript they are the engine's own, so
 * a value computed with them is what scratch-vm computes. On the JVM they are `StrictMath`, which
 * agrees with V8 on most inputs but not all (`exp(1)` differs in the last bit), so the folder only
 * trusts them where [exact] is true.
 */
expect object JsMath {
    /** Whether these are the very functions scratch-vm runs with, bit for bit. */
    val exact: Boolean

    fun sin(x: Double): Double

    fun cos(x: Double): Double

    fun tan(x: Double): Double

    fun asin(x: Double): Double

    fun acos(x: Double): Double

    fun atan(x: Double): Double

    fun log(x: Double): Double

    fun exp(x: Double): Double

    fun pow(base: Double, exponent: Double): Double
}
