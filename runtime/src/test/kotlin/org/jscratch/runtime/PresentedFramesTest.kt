package org.jscratch.runtime

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The window paints the pen layer as it was when the frame ended, not as the next frame is drawing it. */
class PresentedFramesTest {
    private val pen = PenLayer()
    private val frames = PresentedFrames(pen)
    private val thick = PenState().apply { size = 20.0 }

    private fun drawDot() = pen.line(0.0, 0.0, 0.0, 0.0, thick)

    /** Whether the copy has the dot in the middle of the stage. */
    private fun BufferedImage.hasDot(): Boolean = getRGB(width / 2, height / 2) ushr 24 != 0

    private fun shownHasDot(): Boolean = frames.paint { _, copy -> copy.hasDot() }

    @Test
    fun theNextFrameErasingThePenDoesNotReachTheWindow() {
        drawDot()
        frames.present(null)
        pen.clear()
        assertTrue(shownHasDot(), "the copy keeps the finished frame")
        frames.present(null)
        assertFalse(shownHasDot(), "the next frame shows once it is presented")
    }

    @Test
    fun aFrameThatLeavesThePenAloneKeepsItsCopy() {
        drawDot()
        frames.present(null)
        val before = frames.paint { _, copy -> copy }
        frames.present(null)
        assertSame(before, frames.paint { _, copy -> copy }, "nothing to copy when the pen did not change")
        assertTrue(shownHasDot())
    }

    @Test
    fun aSlowPaintKeepsItsCopyWhileFramesGoOn() {
        drawDot()
        frames.present(null)
        frames.paint { _, painting ->
            repeat(3) {
                pen.clear()
                frames.present(null)
                drawDot()
                frames.present(null)
            }
            pen.clear()
            frames.present(null)
            assertTrue(painting.hasDot(), "the copy being painted is never copied into")
        }
        assertFalse(shownHasDot())
    }
}
