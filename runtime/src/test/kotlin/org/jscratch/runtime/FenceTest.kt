package org.jscratch.runtime

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sprite fencing: a sprite moved past the stage edge is clamped so a sliver of it stays visible,
 * with scratch-render's `getFencedPositionOfDrawable` math over the whole costume box. The box
 * counts transparent pixels, which projects rely on: a common trick measures the stage by moving a
 * sprite with a blank costume to a huge position and reading the fenced coordinates back.
 */
class FenceTest {
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A 40 by 40 costume centered on its position, opaque or fully transparent. */
    private class Box(rt: Runtime, name: String, opaque: Boolean) : Sprite(rt, name) {
        init {
            addCostume("box", "box.png", 20.0, 20.0, 1.0)
            val img = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
            if (opaque) {
                for (y in 0 until 40) for (x in 0 until 40) img.setRGB(x, y, 0xFF336699.toInt())
            }
            costumes[0].setImage(img, 20.0, 20.0, 1.0, Silhouette(img))
        }

        override fun runScript(id: Int) {}

        override fun newInstance(): Target = Box(rt, name, true)
    }

    private val rt: Runtime

    init {
        System.setProperty("jscratch.headless", "0")
        rt = Runtime()
    }

    private val box = Box(rt, "Box", opaque = true)
    private val blank = Box(rt, "Blank", opaque = false)

    init {
        rt.addTarget(Backdrop(rt))
        rt.addTarget(box)
        rt.addTarget(blank)
    }

    @Test
    fun movesInsideTheStageAreNotFenced() {
        box.goToXY(100.0, -50.0)
        assertEquals(100.0 to -50.0, box.x to box.y)
    }

    @Test
    fun hugeMovesClampNearTheEdge() {
        // The 40 x 40 box keeps 15 pixels on stage: its left edge (20 left of center) stops at 225.
        box.goToXY(9999999.0, 9999999.0)
        assertEquals(245.0 to 185.0, box.x to box.y)
        box.goToXY(-9999999.0, -9999999.0)
        assertEquals(-245.0 to -185.0, box.x to box.y)
    }

    @Test
    fun blankCostumesAreFencedByTheirBoxToo() {
        // No pixel is opaque, so the tight bounds are empty, but the costume box still fences.
        blank.goToXY(9999999.0, 9999999.0)
        assertEquals(245.0 to 185.0, blank.x to blank.y)
    }
}
