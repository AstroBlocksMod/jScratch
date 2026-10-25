package org.jscratch.runtime

/** Something drawn on the stage that color sensing can sample: a sprite, the pen layer or the backdrop. */
interface ColorLayer {
    /** Premultiplied ARGB at the stage point ([x], [y]), in Scratch coordinates. 0 where nothing is drawn. */
    fun colorAt(x: Double, y: Double): Int
}

/**
 * The CPU path of scratch-render's color sensing: how colors are sampled, blended and compared.
 * Channels are rounded after every step because scratch-render keeps them in `Uint8ClampedArray`s.
 */
internal object ColorSensing {
    /** Mask argument for "no mask": plain `touching color`. Real masks are 24-bit RGB, so never -1. */
    const val NO_MASK = -1

    fun premultiply(argb: Int): Int {
        val a = argb ushr 24
        if (a == 0) return 0
        if (a == 255) return argb
        val f = a / 255.0
        return pack(a.toDouble(), red(argb) * f, green(argb) * f, blue(argb) * f)
    }

    /** Scales a premultiplied color by what a ghost effect leaves of it, as `u_ghost` does. */
    fun fade(color: Int, keep: Double): Int =
        pack((color ushr 24) * keep, red(color) * keep, green(color) * keep, blue(color) * keep)

    /** Whether [color] looks like the target [rgb]: the top 5 bits of red and green and 4 of blue, as Scratch 2 did. */
    fun colorMatches(color: Int, rgb: Int): Boolean = (color xor rgb) and 0xF8F8F0 == 0

    /** Whether a sprite's own pixel [color] has the [mask] color: opaque at all, and the top 6 bits of each channel. */
    fun maskMatches(color: Int, mask: Int): Boolean = color ushr 24 != 0 && (color xor mask) and 0xFCFCFC == 0

    /**
     * What [layers], front to back, show at ([x], [y]) over the white stage behind them, blended like
     * `gl.blendFunc(ONE, ONE_MINUS_SRC_ALPHA)` in scratch-render's `sampleColor3b`. Returns RGB.
     */
    fun blend(layers: List<ColorLayer>, x: Double, y: Double): Int {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var through = 1.0
        for (layer in layers) {
            if (through == 0.0) break
            val c = layer.colorAt(x, y)
            r = clamp(r + red(c) * through)
            g = clamp(g + green(c) * through)
            b = clamp(b + blue(c) * through)
            through *= 1 - (c ushr 24) / 255.0
        }
        r = clamp(r + through * 255)
        g = clamp(g + through * 255)
        b = clamp(b + through * 255)
        return (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun red(c: Int): Int = (c shr 16) and 0xFF

    private fun green(c: Int): Int = (c shr 8) and 0xFF

    private fun blue(c: Int): Int = c and 0xFF

    /** Rounds half to even and clamps to 0..255, like a store into a `Uint8ClampedArray`. */
    fun clamp(v: Double): Double = Math.rint(v).coerceIn(0.0, 255.0)

    fun pack(a: Double, r: Double, g: Double, b: Double): Int =
        (clamp(a).toInt() shl 24) or (clamp(r).toInt() shl 16) or (clamp(g).toInt() shl 8) or clamp(b).toInt()
}
