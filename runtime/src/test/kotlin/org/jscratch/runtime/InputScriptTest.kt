package org.jscratch.runtime

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scripted input from `-Djscratch.input`, run between frames of a headless run. */
class InputScriptTest {
    /** A stage that remembers on which frames it was clicked and its space key hat ran. */
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        val clicks = ArrayList<Long>()
        val spaces = ArrayList<Long>()

        init {
            registerHat(Runtime.HAT_CLICKED, "", 0)
            registerHat(Runtime.HAT_KEY, "space", 1)
        }

        override fun runScript(id: Int) {
            if (id == 0) clicks.add(rt.frameCount) else spaces.add(rt.frameCount)
        }
    }

    private fun run(script: String, frames: Int = 30): Pair<Runtime, Backdrop> {
        val old = System.getProperty("jscratch.headless")
        System.setProperty("jscratch.headless", frames.toString())
        val rt =
            try {
                Runtime()
            } finally {
                if (old == null) {
                    System.clearProperty(
                        "jscratch.headless",
                    )
                } else {
                    System.setProperty("jscratch.headless", old)
                }
            }
        val stage = Backdrop(rt)
        rt.addTarget(stage)
        rt.input = InputScript.parse(script)
        rt.run()
        return rt to stage
    }

    @Test
    fun inputReachesTheFrameAfterItsLine() {
        val (rt, stage) =
            run(
                """
                # menus first
                2 click 10 20
                5 key space
                6 keyup space
                """.trimIndent(),
            )
        assertEquals(listOf(2L), stage.clicks)
        assertEquals(listOf(5L), stage.spaces)
        assertEquals(10.0, rt.mouseX)
        assertEquals(20.0, rt.mouseY)
        assertEquals(false, rt.mouseDown, "a click lets go two frames later")
    }

    @Test
    fun aHeadlessRunLastsUntilTheScriptIsDone() {
        val (rt, _) = run("12 move 0 0")
        assertEquals(12, rt.frameCount, "nothing is running, but input is still to come")
    }

    @Test
    fun aClickLetsGoWhereTheMouseIsThen() {
        val (rt, stage) = run("2 click 0 0\n3 move 100 50")
        assertEquals(listOf(2L), stage.clicks)
        assertEquals(100.0, rt.mouseX, "letting go does not move the mouse back")
        assertEquals(50.0, rt.mouseY)
        assertEquals(false, rt.mouseDown)
    }

    @Test
    fun quitEndsTheRun() {
        val file = File.createTempFile("input", ".png")
        file.delete()
        try {
            val (rt, _) = run("3 quit\n3 screenshot ${file.path}\n20 move 0 0")
            assertEquals(3, rt.frameCount)
            assertFalse(file.exists(), "steps after quit do not run, even on the same frame")
        } finally {
            file.delete()
        }
    }

    @Test
    fun inputDueAtTheFrameLimitStillRuns() {
        val file = File.createTempFile("input", ".png")
        file.delete()
        try {
            val (rt, _) = run("10 screenshot ${file.path}", frames = 10)
            assertEquals(10, rt.frameCount)
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun aFailingStepEndsTheRunWithItsLine() {
        val missing = File(System.getProperty("java.io.tmpdir"), "no-such-dir-${System.nanoTime()}/shot.png")
        val e = assertFailsWith<IllegalStateException> { run("\n4 screenshot ${missing.path}") }
        assertTrue("line 2 (screenshot)" in e.message!!, e.message)
    }

    @Test
    fun aWindowCaptureNeedsAWindow() {
        val e = assertFailsWith<IllegalStateException> { run("1 window shot.png") }
        assertTrue("headless" in e.message!!, e.message)
    }

    @Test
    fun screenshotSavesTheStage() {
        val file = File.createTempFile("input", ".png")
        try {
            run("1 screenshot ${file.path}")
            val img = ImageIO.read(file)
            assertEquals(Runtime.STAGE_WIDTH * 2, img.width)
            assertEquals(Runtime.STAGE_HEIGHT * 2, img.height)
        } finally {
            file.delete()
        }
    }

    @Test
    fun mistakesAreReportedWithTheirLine() {
        val unknown = assertFailsWith<IllegalArgumentException> { InputScript.parse("1 move 0 0\n2 jump") }
        assertTrue("line 2" in unknown.message!!, unknown.message)
        val arity = assertFailsWith<IllegalArgumentException> { InputScript.parse("1 click 4") }
        assertTrue("line 1" in arity.message!!, arity.message)
        assertFailsWith<IllegalArgumentException> { InputScript.parse("soon flag") }
        val number = assertFailsWith<IllegalArgumentException> { InputScript.parse("1 click 4 up") }
        assertTrue("argument 2" in number.message!!, number.message)
        val missing = assertFailsWith<IllegalArgumentException> { InputScript.parse("12") }
        assertTrue("expected a command" in missing.message!!, missing.message)
    }
}
