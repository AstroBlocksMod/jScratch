package org.jscratch.runtime

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Dragging draggable sprites with the mouse, and when a press counts as a click. */
class DragTest {
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A 40 by 40 opaque square centered on its position, counting how often `when this sprite clicked` runs. */
    private class Box(rt: Runtime, name: String) : Sprite(rt, name) {
        @Volatile var clicks = 0

        init {
            addCostume("box", "box.png", 20.0, 20.0, 1.0)
            val img = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 40) for (x in 0 until 40) img.setRGB(x, y, 0xFF336699.toInt())
            costumes[0].setImage(img, 20.0, 20.0, 1.0, Silhouette(img))
            registerHat(Runtime.HAT_CLICKED, "", 0)
        }

        override fun runScript(id: Int) {
            clicks++
        }

        override fun newInstance(): Target = Box(rt, name)
    }

    private val rt: Runtime

    init {
        System.setProperty("jscratch.headless", "0")
        rt = Runtime()
    }

    private val box = Box(rt, "Box")
    private val other = Box(rt, "Other")

    init {
        rt.addTarget(Backdrop(rt))
        rt.addTarget(box)
        rt.addTarget(other)
        other.goToXY(150.0, 0.0)
    }

    private fun mouse(x: Double, y: Double, down: Boolean? = null) {
        rt.postMouse(x, y, down)
        rt.frame()
    }

    @Test
    fun aDraggableSpriteFollowsTheMouse() {
        box.draggable = true
        mouse(5.0, 5.0, true)
        mouse(55.0, 25.0)
        assertEquals(50.0, box.x)
        assertEquals(20.0, box.y)
        assertTrue(box.dragging)
        mouse(75.0, 25.0, false)
        assertEquals(70.0, box.x)
        assertFalse(box.dragging)
        assertEquals(0, box.clicks, "a press that became a drag is not a click")
    }

    @Test
    fun aDraggableSpriteIsClickedWhenTheMouseGoesUp() {
        box.draggable = true
        mouse(5.0, 5.0, true)
        assertEquals(0, box.clicks)
        mouse(6.0, 5.0, false)
        assertEquals(1, box.clicks)
        assertEquals(0.0, box.x, "moving less than the threshold does not drag")
    }

    @Test
    fun otherSpritesAreClickedWhenTheMouseGoesDownAndNeverDragged() {
        mouse(5.0, 5.0, true)
        assertEquals(1, box.clicks)
        mouse(55.0, 5.0)
        mouse(55.0, 5.0, false)
        assertEquals(0.0, box.x)
        assertEquals(1, box.clicks)
    }

    @Test
    fun aReleaseFarFromThePressDragsEvenWithoutAFrameInBetween() {
        box.draggable = true
        rt.postMouse(5.0, 5.0, true)
        rt.postMouse(45.0, 5.0, false)
        rt.frame()
        assertEquals(40.0, box.x)
        assertEquals(0, box.clicks)
    }

    @Test
    fun scriptsCannotMoveADraggedSprite() {
        box.draggable = true
        mouse(0.0, 0.0, true)
        mouse(10.0, 0.0)
        box.goToXY(-100.0, -100.0)
        assertEquals(10.0, box.x)
        mouse(10.0, 0.0, false)
        box.goToXY(-100.0, -100.0)
        assertEquals(-100.0, box.x)
    }

    @Test
    fun draggingBringsTheSpriteToTheFront() {
        box.draggable = true
        assertSame(other, rt.sprites.last())
        mouse(0.0, 0.0, true)
        mouse(10.0, 0.0)
        assertSame(box, rt.sprites.last())
    }

    @Test
    fun nothingTouchesADraggedSprite() {
        other.draggable = true
        mouse(150.0, 0.0, true)
        mouse(30.0, 0.0)
        assertFalse(box.touching("Other"), "the dragged sprite is invisible to others")
        assertTrue(other.touching("Box"), "but it touches them")
        mouse(30.0, 0.0, false)
        assertTrue(box.touching("Other"))
    }

    @Test
    fun aPressHeldStillBecomesADrag() {
        box.draggable = true
        mouse(0.0, 0.0, true)
        Thread.sleep(450)
        rt.frame()
        assertTrue(box.dragging)
        mouse(0.0, 0.0, false)
        assertEquals(0, box.clicks)
    }
}
