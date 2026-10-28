package org.jscratch.runtime

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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

    @Test
    fun renderShrinksToTheDrawnSize() {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) img.setRGB(x, y, 0xFFFF0000.toInt())
        val small = effect("color", 100.0).render(Silhouette(img), 4.0, 4.0, 2, 2)
        assertEquals(2, small.width)
        assertEquals(2, small.height)
        // Solid red everywhere, so the bilinear samples stay pure and every pixel turns cyan.
        for (y in 0 until 2) for (x in 0 until 2) assertEquals(0xFF01FFFF.toInt(), small.getRGB(x, y))
    }

    @Test
    fun renderSplitsLargeImagesAcrossCores() {
        // 256 x 256 is past the parallel threshold; every pixel must still come out transformed.
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 256) for (x in 0 until 256) img.setRGB(x, y, 0xFF000000.toInt())
        val bright = effect("brightness", 100.0).render(Silhouette(img), 256.0, 256.0)
        for (y in 0 until 256 step 51) {
            for (x in 0 until 256 step 51) {
                assertEquals(0xFFFFFFFF.toInt(), bright.getRGB(x, y))
            }
        }
    }

    @Test
    fun theColorCacheMatchesTransformColorForEveryColor() {
        // 4096 distinct opaque colors, as many as the cache has slots, so plenty of them share a slot.
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val random = java.util.Random(1)
        for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, 0xFF000000.toInt() or random.nextInt(0x1000000))
        val e = effect("color", 40.0).with("brightness", 30.0)
        val out = e.render(Silhouette(img), 64.0, 64.0)
        for (y in 0 until 64) for (x in 0 until 64) assertEquals(e.transformColor(img.getRGB(x, y)), out.getRGB(x, y))
    }

    @Test
    fun targetsWearingTheSameImageShareEffectedVersions() {
        val sil = Silhouette(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))
        val e = effect("brightness", 10.0)
        val half = sil.effected(8.0, 8.0, e, 0.5)
        assertSame(half, sil.effected(8.0, 8.0, e, 0.5))
        assertNotSame(half, sil.effected(8.0, 8.0, e, 1.0))
        // It keeps only the last few: four more push both of those out.
        for (v in 1..4) sil.effected(8.0, 8.0, effect("color", v.toDouble()), 1.0)
        assertNotSame(half, sil.effected(8.0, 8.0, e, 0.5))
    }

    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A 64 by 64 opaque square. */
    private class Box(rt: Runtime) : Sprite(rt, "Box") {
        init {
            addCostume("box", "box.png", 32.0, 32.0, 1.0)
            val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, 0xFF336699.toInt())
            costumes[0].setImage(img, 32.0, 32.0, 1.0, Silhouette(img))
        }

        override fun runScript(id: Int) {}

        override fun newInstance(): Target = Box(rt)
    }

    @Test
    fun effectsRenderAsSharpAsTheWindowDraws() {
        System.setProperty("jscratch.headless", "0")
        val rt = Runtime()
        val box = Box(rt)
        rt.addTarget(Backdrop(rt))
        rt.addTarget(box)
        box.setSize(25.0)
        box.setEffect("brightness", 10.0)
        // The pen layer draws at twice the stage size, so a quarter-size sprite needs half its pixels.
        assertEquals(32, box.drawnImage()!!.width)
        // A large window on a HiDPI screen draws 8 device pixels per stage unit: all of them.
        rt.windowDeviceScale = 8.0
        assertEquals(64, box.drawnImage()!!.width)
    }
}
