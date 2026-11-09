package org.jscratch.sb3o.values

/**
 * The transcendental functions of JavaScript's `Math`: the engine's own on JavaScript, `StrictMath` (the
 * fdlibm algorithms JavaScript engines derive theirs from) on the JVM. Engines do not agree on these to
 * the last bit, not even two JavaScript engines, so nothing may treat a result as the one every player
 * computes. The folder only uses them where the answer is rounded far enough that the last bits cannot
 * show (see `FoldConstants`).
 */
expect object JsMath {
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
