package org.jscratch.runtime

import java.awt.image.BufferedImage

/**
 * The graphic effects of a target besides ghost, which [Target] keeps in its own field. Holds the
 * values the blocks set and turns them into scratch-render's shader uniforms with the converters in
 * its `ShaderManager`. An effect is on while its value is not 0. Instances never change, so equal
 * effects can share a cached image.
 */
internal data class Effects(
    val color: Double,
    val brightness: Double,
    val fisheye: Double,
    val whirl: Double,
    val pixelate: Double,
    val mosaic: Double,
) {
    private val uColor = color / 200 % 1
    private val uBrightness = brightness / 100
    private val uFisheye = Math.max(0.0, (fisheye + 100) / 100)
    private val uWhirl = -whirl * Math.PI / 180
    private val uPixelate = Math.abs(pixelate) / 10
    private val uMosaic = Math.floor((Math.abs(mosaic) + 10) / 10 + 0.5).coerceIn(1.0, 512.0)

    /** Whether an effect moves pixels around, which changes what the sprite touches and its bounds. */
    val changesShape: Boolean = fisheye != 0.0 || whirl != 0.0 || pixelate != 0.0 || mosaic != 0.0
    val changesColor: Boolean = color != 0.0 || brightness != 0.0
    val active: Boolean = changesShape || changesColor

    /** The value of the effect called [name] (lower case), or null when there is no such effect. */
    fun get(name: String): Double? = when (name) {
        "color" -> color
        "brightness" -> brightness
        "fisheye" -> fisheye
        "whirl" -> whirl
        "pixelate" -> pixelate
        "mosaic" -> mosaic
        else -> null
    }

    /**
     * A copy with the effect called [name] set to [value]. Brightness is clamped to -100..100, as
     * scratch-vm does. Unknown names change nothing.
     */
    fun with(name: String, value: Double): Effects = when (name) {
        "color" -> copy(color = value)
        "brightness" -> copy(brightness = value.coerceIn(-100.0, 100.0))
        "fisheye" -> copy(fisheye = value)
        "whirl" -> copy(whirl = value)
        "pixelate" -> copy(pixelate = value)
        "mosaic" -> copy(mosaic = value)
        else -> this
    }

    /**
     * Moves the texture coordinates in [p] (0 to 1, y down) to where the shader samples the costume
     * for them, as `EffectTransform.transformPoint` does. [skinWidth] and [skinHeight] are the
     * costume's size in stage units, which pixelate measures its blocks in.
     */
    fun transformPoint(p: DoubleArray, skinWidth: Double, skinHeight: Double) {
        var u = p[0]
        var v = p[1]
        if (mosaic != 0.0) {
            u = uMosaic * u % 1
            v = uMosaic * v % 1
        }
        if (pixelate != 0.0) {
            val texelsX = skinWidth / uPixelate
            val texelsY = skinHeight / uPixelate
            u = (Math.floor(u * texelsX) + 0.5) / texelsX
            v = (Math.floor(v * texelsY) + 0.5) / texelsY
        }
        if (whirl != 0.0) {
            val ox = u - 0.5
            val oy = v - 0.5
            val factor = Math.max(1 - Math.sqrt(ox * ox + oy * oy) / 0.5, 0.0)
            val angle = uWhirl * factor * factor
            val sin = Math.sin(angle)
            val cos = Math.cos(angle)
            u = cos * ox + sin * oy + 0.5
            v = -sin * ox + cos * oy + 0.5
        }
        if (fisheye != 0.0) {
            val vx = (u - 0.5) / 0.5
            val vy = (v - 0.5) / 0.5
            val length = Math.sqrt(vx * vx + vy * vy)
            // The shader divides by zero at the very center. Leave that point where it is.
            if (length > 0) {
                val r = Math.pow(Math.min(length, 1.0), uFisheye) * Math.max(1.0, length)
                u = 0.5 + r * vx / length * 0.5
                v = 0.5 + r * vy / length * 0.5
            }
        }
        p[0] = u
        p[1] = v
    }

    /**
     * Applies color and brightness to a premultiplied color, as `EffectTransform.transformColor` does
     * without ghost. Channels are rounded after every step, like its `Uint8ClampedArray`.
     */
    fun transformColor(color: Int): Int {
        val a = color ushr 24
        if (a == 0 || !changesColor) return color
        val alpha = a / 255.0
        var r = ColorSensing.clamp(((color shr 16) and 0xFF) / alpha)
        var g = ColorSensing.clamp(((color shr 8) and 0xFF) / alpha)
        var b = ColorSensing.clamp((color and 0xFF) / alpha)
        if (this.color != 0.0) {
            // scratch-render's rgbToHsv.
            var hi = r / 255
            var mid = g / 255
            var lo = b / 255
            var k = 0.0
            if (mid < lo) {
                val t = mid
                mid = lo
                lo = t
                k = -1.0
            }
            if (hi < mid) {
                val t = hi
                hi = mid
                mid = t
                k = -2.0 / 6 - k
            }
            val chroma = hi - Math.min(mid, lo)
            var hue = Math.abs(k + (mid - lo) / (6 * chroma + EPSILON))
            var sat = chroma / (hi + EPSILON)
            var value = hi
            // Gray and near black get a little saturation so a hue change shows.
            if (value < MIN_VALUE) {
                hue = 0.0
                sat = 1.0
                value = MIN_VALUE
            } else if (sat < MIN_SATURATION) {
                hue = 0.0
                sat = MIN_SATURATION
            }
            // scratch-render's hsvToRgb. Saturation is never 0 here, so its gray case is left out.
            hue = (uColor + hue + 1) % 1
            val i = (hue * 6).toInt()
            val f = hue * 6 - i
            val p = value * (1 - sat)
            val q = value * (1 - sat * f)
            val t = value * (1 - sat * (1 - f))
            val red =
                when (i) {
                    0, 5 -> value
                    1 -> q
                    2, 3 -> p
                    else -> t
                }
            val green =
                when (i) {
                    0 -> t
                    1, 2 -> value
                    3 -> q
                    else -> p
                }
            val blue =
                when (i) {
                    0, 1 -> p
                    2 -> t
                    3, 4 -> value
                    else -> q
                }
            r = ColorSensing.clamp(red * 255 + 0.5)
            g = ColorSensing.clamp(green * 255 + 0.5)
            b = ColorSensing.clamp(blue * 255 + 0.5)
        }
        if (brightness != 0.0) {
            val add = uBrightness * 255
            r = ColorSensing.clamp(r + add)
            g = ColorSensing.clamp(g + add)
            b = ColorSensing.clamp(b + add)
        }
        return ColorSensing.pack(a.toDouble(), r * alpha, g * alpha, b * alpha)
    }

    /**
     * Draws [sil]'s costume image with these effects, as scratch-render's sprite shader does. Moved
     * pixels are sampled bilinearly from the premultiplied costume, clamped at its edges like a WebGL
     * texture. The result is [outWidth] by [outHeight] pixels, at most the costume's own size: a
     * sprite drawn small gets a small image, so the effects only pay for the pixels that end up on
     * the stage. The work is split across cores for large images, and [transformColor] results are
     * cached by color, which a drawing with flat areas hits constantly. Comes back
     * premultiplied.
     */
    fun render(
        sil: Silhouette,
        skinWidth: Double,
        skinHeight: Double,
        outWidth: Int = sil.width,
        outHeight: Int = sil.height,
    ): BufferedImage {
        val src = sil.premultiplied
        val out = IntArray(outWidth * outHeight)
        val cores = java.lang.Runtime.getRuntime().availableProcessors()
        val chunks = if (outWidth * outHeight >= PARALLEL_PIXELS) Math.min(cores, outHeight) else 1
        if (chunks <= 1) {
            renderRows(sil, src, skinWidth, skinHeight, out, outWidth, outHeight, 0, outHeight)
        } else {
            val rows = (outHeight + chunks - 1) / chunks
            java.util.stream.IntStream.range(0, chunks).parallel().forEach { c ->
                val from = c * rows
                val to = Math.min(outHeight, from + rows)
                renderRows(sil, src, skinWidth, skinHeight, out, outWidth, outHeight, from, to)
            }
        }
        val img = BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB_PRE)
        img.raster.setDataElements(0, 0, outWidth, outHeight, out)
        return img
    }

    /** Renders the rows [fromRow] until [toRow] of [out], with its own color cache so chunks never share state. */
    private fun renderRows(
        sil: Silhouette,
        src: IntArray,
        skinWidth: Double,
        skinHeight: Double,
        out: IntArray,
        outWidth: Int,
        outHeight: Int,
        fromRow: Int,
        toRow: Int,
    ) {
        val w = sil.width
        val h = sil.height
        val plain = !changesShape && outWidth == w && outHeight == h
        // A direct-mapped cache of transformColor: a slot keeps the last color that hashed to it, so a
        // lookup is two array reads and never allocates. Keys are the colors as unsigned longs, and -1
        // marks an empty slot, which no color can be.
        val keys = if (changesColor) LongArray(COLOR_CACHE_SLOTS) { -1L } else null
        val values = if (changesColor) IntArray(COLOR_CACHE_SLOTS) else null
        val p = DoubleArray(2)
        for (y in fromRow until toRow) {
            for (x in 0 until outWidth) {
                val i = y * outWidth + x
                val c =
                    when {
                        plain -> src[i]
                        changesShape -> {
                            p[0] = (x + 0.5) / outWidth
                            p[1] = (y + 0.5) / outHeight
                            transformPoint(p, skinWidth, skinHeight)
                            sampleLinear(src, w, h, p[0], p[1])
                        }
                        else -> sampleLinear(src, w, h, (x + 0.5) / outWidth, (y + 0.5) / outHeight)
                    }
                out[i] =
                    if (keys == null || values == null) {
                        c
                    } else {
                        val slot = (c * GOLDEN_RATIO) ushr (32 - COLOR_CACHE_BITS)
                        val key = c.toLong() and 0xFFFFFFFFL
                        if (keys[slot] != key) {
                            keys[slot] = key
                            values[slot] = transformColor(c)
                        }
                        values[slot]
                    }
            }
        }
    }

    private fun sampleLinear(src: IntArray, w: Int, h: Int, u: Double, v: Double): Int {
        val fx = u * w - 0.5
        val fy = v * h - 0.5
        val x0 = Math.floor(fx)
        val y0 = Math.floor(fy)
        val tx = fx - x0
        val ty = fy - y0
        val left = x0.toInt().coerceIn(0, w - 1)
        val right = (x0.toInt() + 1).coerceIn(0, w - 1)
        val top = y0.toInt().coerceIn(0, h - 1) * w
        val bottom = (y0.toInt() + 1).coerceIn(0, h - 1) * w
        val c00 = src[top + left]
        val c10 = src[top + right]
        val c01 = src[bottom + left]
        val c11 = src[bottom + right]
        var result = 0
        for (shift in 0..24 step 8) {
            val upper = ((c00 ushr shift) and 0xFF) * (1 - tx) + ((c10 ushr shift) and 0xFF) * tx
            val lower = ((c01 ushr shift) and 0xFF) * (1 - tx) + ((c11 ushr shift) and 0xFF) * tx
            val channel = Math.rint(upper * (1 - ty) + lower * ty).toInt().coerceIn(0, 255)
            result = result or (channel shl shift)
        }
        return result
    }

    companion object {
        val NONE = Effects(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        private const val MIN_VALUE = 0.11 / 2.0
        private const val MIN_SATURATION = 0.09

        /** JavaScript's `Number.EPSILON`. */
        private val EPSILON = Math.ulp(1.0)

        /** Output pixels from which [render] splits its rows across cores. */
        private const val PARALLEL_PIXELS = 1 shl 15

        /** The color cache of each render chunk has 2 to this many slots. */
        private const val COLOR_CACHE_BITS = 12
        private const val COLOR_CACHE_SLOTS = 1 shl COLOR_CACHE_BITS

        /** Fibonacci hashing spreads colors that differ in a few low bits over the cache's slots. */
        private const val GOLDEN_RATIO = -0x61c88647
    }
}

/**
 * A costume image as a target's graphic effects draw it, at [scale] times the costume's resolution
 * (at most 1): the resolution the target is drawn at, so a large texture stamped small never pays
 * for its full size. Made through [Silhouette.effected], which shares it among every target that wears
 * the image, and drawn on first use.
 */
internal class EffectedCostume(
    val source: Silhouette,
    private val skinWidth: Double,
    private val skinHeight: Double,
    val effects: Effects,
    val scale: Double,
) {
    val width: Int = Math.max(1, Math.round(source.width * scale).toInt())
    val height: Int = Math.max(1, Math.round(source.height * scale).toInt())

    val image: BufferedImage by lazy { effects.render(source, skinWidth, skinHeight, width, height) }

    /** The outline of [image], for bounds. Touching samples the costume through the effects instead. */
    val silhouette: Silhouette by lazy { Silhouette(image) }
}
