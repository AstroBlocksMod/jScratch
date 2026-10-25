package org.jscratch.runtime

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffectsTest {
    private fun effect(name: String, value: Double) = Effects.NONE.with(name, value)

    private fun moved(e: Effects, u: Double, v: Double, skin: Double = 100.0): Pair<Double, Double> {
        val p = doubleArrayOf(u, v)
        e.transformPoint(p, skin, skin)
        return p[0] to p[1]
    }

    @Test
    fun effectsAreOnWhileNotZero() {
        assertFalse(Effects.NONE.active)
        assertTrue(effect("color", 1.0).changesColor)
        assertFalse(effect("color", 1.0).changesShape)
        assertTrue(effect("whirl", -5.0).changesShape)
        assertEquals(Effects.NONE, effect("color", 25.0).with("color", 0.0))
        assertEquals(null, Effects.NONE.get("ghost"))
    }

    @Test
    fun brightnessIsClampedLikeScratchVm() {
        assertEquals(100.0, effect("brightness", 300.0).brightness)
        assertEquals(-100.0, effect("brightness", -300.0).brightness)
        // Other effects keep any value.
        assertEquals(300.0, effect("whirl", 300.0).whirl)
    }

    @Test
    fun colorShiftsTheHue() {
        // 100 is half way around the color wheel: red turns cyan. scratch-render's saturation comes out
        // a hair under 1, which leaves 1 in the red channel.
        assertEquals(0xFF01FFFF.toInt(), effect("color", 100.0).transformColor(0xFFFF0000.toInt()))
        assertEquals(0xFF01FFFF.toInt(), effect("color", -100.0).transformColor(0xFFFF0000.toInt()))
        // 200 goes all the way around.
        assertEquals(0xFFFF0101.toInt(), effect("color", 200.0).transformColor(0xFFFF0000.toInt()))
        // Scratch blue, as the effects sample checks it.
        assertEquals(0xFFFFB54D.toInt(), effect("color", 100.0).transformColor(0xFF4C97FF.toInt()))
    }

    @Test
    fun colorGivesGraysSomeSaturation() {
        // Black becomes a very dark red before the hue moves, so it shows a little cyan.
        assertEquals(0xFF000F0F.toInt(), effect("color", 100.0).transformColor(0xFF000000.toInt()))
    }

    @Test
    fun colorWorksOnUnpremultipliedChannels() {
        assertEquals(0x80018080.toInt(), effect("color", 100.0).transformColor(0x80800000.toInt()))
        assertEquals(0, effect("color", 100.0).transformColor(0))
    }

    @Test
    fun brightnessAddsToEveryChannel() {
        assertEquals(0xFFFFFFFF.toInt(), effect("brightness", 100.0).transformColor(0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), effect("brightness", -100.0).transformColor(0xFF4C97FF.toInt()))
        // 127.5 rounds half to even.
        assertEquals(0xFF808080.toInt(), effect("brightness", 50.0).transformColor(0xFF000000.toInt()))
    }

    @Test
    fun mosaicRepeatsTheCostume() {
        // 90 makes ten copies across.
        val (u, v) = moved(effect("mosaic", 90.0), 0.05, 0.25)
        assertEquals(0.5, u, 1e-9)
        assertEquals(0.5, v, 1e-9)
    }

    @Test
    fun pixelateSamplesBlockCenters() {
        // Blocks are a tenth of the value in stage units: 10 units on a 100 unit costume.
        val (u, v) = moved(effect("pixelate", 100.0), 0.23, 0.01)
        assertEquals(0.25, u, 1e-9)
        assertEquals(0.05, v, 1e-9)
    }

    @Test
    fun whirlTurnsTheMiddleOnly() {
        val e = effect("whirl", 90.0)
        assertEquals(0.5 to 0.5, moved(e, 0.5, 0.5))
        assertEquals(0.0 to 0.5, moved(e, 0.0, 0.5))
        val (u, v) = moved(e, 0.75, 0.5)
        // A quarter of the way out turns by a quarter of 90 degrees, counterclockwise in image space.
        assertEquals(0.5 + 0.25 * Math.cos(Math.PI / 8), u, 1e-9)
        assertEquals(0.5 + 0.25 * Math.sin(Math.PI / 8), v, 1e-9)
    }

    @Test
    fun fisheyeBulgesTheMiddle() {
        val e = effect("fisheye", 100.0)
        assertEquals(0.5 to 0.5, moved(e, 0.5, 0.5))
        // Corners stay put.
        assertEquals(1.0 to 1.0, moved(e, 1.0, 1.0))
        val (u, _) = moved(e, 0.75, 0.5)
        assertEquals(0.625, u, 1e-9)
    }

    @Test
    fun renderKeepsTheSizeAndAppliesEveryEffect() {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0xFFFF0000.toInt())
        val sil = Silhouette(img)
        val colored = effect("color", 100.0).render(sil, 4.0, 4.0)
        assertEquals(4, colored.width)
        assertEquals(0xFF01FFFF.toInt(), colored.getRGB(0, 0))
        assertEquals(0, colored.getRGB(1, 1) ushr 24)
        // Mosaic 10 makes two copies across, so the red corner pixel shows up in every quarter.
        val tiled = Silhouette(effect("mosaic", 10.0).render(sil, 4.0, 4.0))
        assertTrue(tiled.opaque(0, 0) && tiled.opaque(2, 0) && tiled.opaque(0, 2) && tiled.opaque(2, 2))
        assertFalse(tiled.opaque(1, 1))
    }
}
