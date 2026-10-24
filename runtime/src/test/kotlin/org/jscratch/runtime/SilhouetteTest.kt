package org.jscratch.runtime

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilhouetteTest {
    /** A 10 x 10 transparent image with an opaque square from (2, 3) to (5, 6) inclusive. */
    private fun square(): BufferedImage {
        val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(2, 3, 4, 4)
        g.dispose()
        return img
    }

    @Test
    fun hullHugsTheOpaquePixels() {
        val hull = Silhouette(square()).hull
        val xs = hull.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = hull.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(2.0, xs.min())
        assertEquals(6.0, xs.max())
        assertEquals(3.0, ys.min())
        assertEquals(7.0, ys.max())
        // Collinear points are dropped, so a square keeps only its corners.
        assertEquals(8, hull.size)
    }

    @Test
    fun transparentImageHasNoHull() {
        assertContentEquals(DoubleArray(0), Silhouette(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)).hull)
    }

    @Test
    fun nearestTestsSinglePixels() {
        val s = Silhouette(square())
        assertTrue(s.isTouchingNearest(2.0, 3.0))
        assertTrue(s.isTouchingNearest(5.9, 6.9))
        assertFalse(s.isTouchingNearest(6.0, 6.0))
        assertFalse(s.isTouchingNearest(-1.0, 4.0))
        assertFalse(s.isTouchingNearest(20.0, 4.0))
    }
}
