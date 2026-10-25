package org.jscratch.runtime

import java.awt.image.BufferedImage

/**
 * The opaque pixels of a costume image, used for collision and tight bounds the
 * way scratch-render's `Silhouette` is. Built lazily on first use and shared by
 * every costume (and clone) that uses the same image.
 */
class Silhouette(private val image: BufferedImage) {
    val width: Int = image.width
    val height: Int = image.height

    private val data: Data by lazy { build() }

    /**
     * Convex hull of the opaque pixels in image pixel coordinates, as x, y pairs.
     * Empty when the image is fully transparent.
     */
    val hull: DoubleArray get() = data.hull

    private class Data(val mask: LongArray, val hull: DoubleArray)

    /** ARGB pixels, read only once a color sensing block needs them so other projects do not pay for a copy. */
    private val argb: IntArray by lazy { image.getRGB(0, 0, width, height, null, 0, width) }

    /**
     * Premultiplied ARGB pixels, read only once a graphic effect other than ghost needs them. Built
     * straight from the image so effects alone do not also keep [argb].
     */
    internal val premultiplied: IntArray by lazy {
        val px = image.getRGB(0, 0, width, height, null, 0, width)
        for (i in px.indices) px[i] = ColorSensing.premultiply(px[i])
        px
    }

    /** Whether the pixel at integer image coordinates ([x], [y]) is opaque. Out of range is transparent. */
    fun opaque(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val i = y * width + x
        return (data.mask[i ushr 6] ushr (i and 63)) and 1L != 0L
    }

    /** Nearest-neighbor test at image coordinates ([px], [py]). */
    fun isTouchingNearest(px: Double, py: Double): Boolean = opaque(Math.floor(px).toInt(), Math.floor(py).toInt())

    /**
     * Linear test at image coordinates ([px], [py]): true when any of the four
     * pixels a bilinear sample would read is opaque, as in scratch-render. Points
     * outside the image never hit.
     */
    fun isTouchingLinear(px: Double, py: Double): Boolean {
        if (px < 0 || py < 0 || px >= width || py >= height) return false
        val x = Math.floor(px / width * (width - 1)).toInt()
        val y = Math.floor(py / height * (height - 1)).toInt()
        return opaque(x, y) || opaque(x + 1, y) || opaque(x, y + 1) || opaque(x + 1, y + 1)
    }

    /**
     * Premultiplied ARGB at image coordinates ([px], [py]), picked the way scratch-render's
     * `colorAtNearest` picks it. Points outside the image are transparent.
     */
    fun colorAtNearest(px: Double, py: Double): Int {
        if (px < 0 || py < 0 || px > width || py > height) return 0
        val x = Math.floor(px / width * (width - 1)).toInt()
        val y = Math.floor(py / height * (height - 1)).toInt()
        return ColorSensing.premultiply(argb[y * width + x])
    }

    private fun build(): Data {
        val argb = image.getRGB(0, 0, width, height, null, 0, width)
        val mask = LongArray((width * height + 63) / 64)
        // Each row contributes the outer corners of its leftmost and rightmost opaque pixel.
        val points = ArrayList<DoubleArray>()
        for (y in 0 until height) {
            var left = -1
            var right = -1
            val row = y * width
            for (x in 0 until width) {
                if (argb[row + x] ushr 24 == 0) continue
                val i = row + x
                mask[i ushr 6] = mask[i ushr 6] or (1L shl (i and 63))
                if (left < 0) left = x
                right = x
            }
            if (left < 0) continue
            points.add(doubleArrayOf(left.toDouble(), y.toDouble()))
            points.add(doubleArrayOf(left.toDouble(), y + 1.0))
            points.add(doubleArrayOf(right + 1.0, y.toDouble()))
            points.add(doubleArrayOf(right + 1.0, y + 1.0))
        }
        return Data(mask, convexHull(points))
    }

    companion object {
        /** Andrew's monotone chain. Returns the hull as flat x, y pairs. */
        fun convexHull(input: List<DoubleArray>): DoubleArray {
            val pts = input.sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }))
            if (pts.size < 3) return DoubleArray(pts.size * 2) { pts[it / 2][it % 2] }
            val hull = arrayOfNulls<DoubleArray>(pts.size * 2)
            var k = 0
            for (p in pts) {
                while (k >= 2 && cross(hull[k - 2]!!, hull[k - 1]!!, p) <= 0) k--
                hull[k++] = p
            }
            val lower = k + 1
            for (i in pts.size - 2 downTo 0) {
                val p = pts[i]
                while (k >= lower && cross(hull[k - 2]!!, hull[k - 1]!!, p) <= 0) k--
                hull[k++] = p
            }
            // The last point repeats the first.
            val n = k - 1
            return DoubleArray(n * 2) { hull[it / 2]!![it % 2] }
        }

        private fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray): Double =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    }
}
