package org.jscratch.runtime

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorSensingTest {
    private fun solid(argb: Int) = object : ColorLayer {
        override fun colorAt(x: Double, y: Double): Int = argb
    }

    @Test
    fun premultipliesAndRounds() {
        assertEquals(0x80800000.toInt(), ColorSensing.premultiply(0x80FF0000.toInt()))
        assertEquals(0xFF123456.toInt(), ColorSensing.premultiply(0xFF123456.toInt()))
        assertEquals(0, ColorSensing.premultiply(0x00FFFFFF))
    }

    @Test
    fun ghostFadesEveryChannel() {
        assertEquals(0x80800000.toInt(), ColorSensing.fade(0xFFFF0000.toInt(), 0.5))
    }

    @Test
    fun blendsFrontToBackOverWhite() {
        assertEquals(0xFFFFFF, ColorSensing.blend(emptyList(), 0.0, 0.0))
        // Half-transparent red over the white stage.
        assertEquals(0xFF7F7F, ColorSensing.blend(listOf(solid(0x80800000.toInt())), 0.0, 0.0))
        // An opaque layer hides everything behind it.
        val layers = listOf(solid(0xFF0000FF.toInt()), solid(0xFFFF0000.toInt()))
        assertEquals(0x0000FF, ColorSensing.blend(layers, 0.0, 0.0))
    }

    @Test
    fun colorsMatchOnTheirTopBits() {
        assertTrue(ColorSensing.colorMatches(0x4C97FF, 0x4C97FF))
        // Red and green keep 5 bits, blue keeps 4.
        assertTrue(ColorSensing.colorMatches(0x4F90F0, 0x4C97FF))
        assertFalse(ColorSensing.colorMatches(0x4497FF, 0x4C97FF))
        assertFalse(ColorSensing.colorMatches(0x4C97EF, 0x4C97FF))
    }

    @Test
    fun masksNeedAnOpaquePixel() {
        assertTrue(ColorSensing.maskMatches(0xFF9966FF.toInt(), 0x9966FF))
        assertFalse(ColorSensing.maskMatches(0x009966FF, 0x9966FF))
        // Masks keep 6 bits of every channel.
        assertFalse(ColorSensing.maskMatches(0xFF9966F0.toInt(), 0x9966FF))
    }

    @Test
    fun silhouetteColorsAreSampledByNearestNeighbor() {
        val img = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0xFFFF0000.toInt())
        img.setRGB(3, 0, 0x800000FF.toInt())
        val s = Silhouette(img)
        assertEquals(0xFFFF0000.toInt(), s.colorAtNearest(0.0, 0.5))
        // The right edge maps onto the last pixel, which comes back premultiplied.
        assertEquals(0x80000080.toInt(), s.colorAtNearest(4.0, 0.5))
        assertEquals(0, s.colorAtNearest(-0.1, 0.5))
        assertEquals(0, s.colorAtNearest(4.1, 0.5))
    }
}
