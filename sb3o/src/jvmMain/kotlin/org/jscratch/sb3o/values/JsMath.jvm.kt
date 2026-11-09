package org.jscratch.sb3o.values

actual object JsMath {
    actual fun sin(x: Double): Double = StrictMath.sin(x)

    actual fun cos(x: Double): Double = StrictMath.cos(x)

    actual fun tan(x: Double): Double = StrictMath.tan(x)

    actual fun asin(x: Double): Double = StrictMath.asin(x)

    actual fun acos(x: Double): Double = StrictMath.acos(x)

    actual fun atan(x: Double): Double = StrictMath.atan(x)

    actual fun log(x: Double): Double = StrictMath.log(x)

    actual fun exp(x: Double): Double = StrictMath.exp(x)

    actual fun pow(base: Double, exponent: Double): Double = StrictMath.pow(base, exponent)
}
