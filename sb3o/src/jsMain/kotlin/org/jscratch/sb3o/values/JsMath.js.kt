package org.jscratch.sb3o.values

import kotlin.math.pow

actual object JsMath {
    actual fun sin(x: Double): Double = kotlin.math.sin(x)

    actual fun cos(x: Double): Double = kotlin.math.cos(x)

    actual fun tan(x: Double): Double = kotlin.math.tan(x)

    actual fun asin(x: Double): Double = kotlin.math.asin(x)

    actual fun acos(x: Double): Double = kotlin.math.acos(x)

    actual fun atan(x: Double): Double = kotlin.math.atan(x)

    actual fun log(x: Double): Double = kotlin.math.ln(x)

    actual fun exp(x: Double): Double = kotlin.math.exp(x)

    actual fun pow(base: Double, exponent: Double): Double = base.pow(exponent)
}
