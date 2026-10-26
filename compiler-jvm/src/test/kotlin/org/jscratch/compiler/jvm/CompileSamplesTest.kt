package org.jscratch.compiler.jvm

import org.jscratch.compiler.JScratch
import org.jscratch.compiler.sb3.Sb3Parser
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end: compile the sample projects with the real runtime, run the JAR
 * headless in a child JVM and check what the sprites said.
 */
class CompileSamplesTest {
    private val runtimeJar = File(System.getProperty("jscratch.test.runtimeJar"))
    private val samplesDir = File(System.getProperty("jscratch.test.samplesDir"))
    private val java = File(System.getProperty("jscratch.test.javaHome"), "bin/java")
    private val json = RhinoProjectJson()

    @Test
    fun parsesHelloProject() {
        val sb3 = Sb3Parser(json).readSb3(File(samplesDir, "hello.sb3").readBytes())
        val project = sb3.project
        assertEquals(2, project.targets.size)
        assertEquals("Cat", project.sprites.single().name)
        assertEquals(listOf("score"), project.stage.variables.values.map { it.name })
        assertEquals(listOf("apple", "banana"), project.stage.lists.values.single().items)
        assertTrue(sb3.assets.containsKey("6ac3a6339b85092fa9b1694f2da7779d.svg"))
        val call = project.sprites.single().blocks.getValue("s10")
        assertEquals("square %s", call.mutation?.proccode)
        assertEquals(listOf("arg1"), call.mutation?.argumentIds)
    }

    @Test
    fun compilesAndRunsHelloProject() {
        val result = JScratch.compile(File(samplesDir, "hello.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertTrue(result.classes.containsKey("jscratch/project/Main"))
        assertTrue(result.classes.containsKey("jscratch/project/Stage"))
        assertTrue(result.classes.containsKey("jscratch/project/Sprite_Cat"))
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val listing = JScratch.disassemble(result.classes.getValue("jscratch/project/Sprite_Cat"))
        assertTrue(listing.contains("script_0()V"), "script method missing from listing")
        assertTrue(listing.contains("proc_square_s"), "procedure method missing from listing")

        val out = runHeadless(result.jar, frames = 40)
        val expected =
            listOf(
                "Cat says: score: 1",
                "Cat says: score: 2",
                "Cat says: score: 3",
                "Cat says: 4",
                "Cat says: abcy",
                "Cat says: lt ok",
                "Cat says: banana",
                "Cat says: 49",
                "Cat says: pong",
                "Cat says: done",
            )
        assertEquals(expected, out.lines().filter { it.startsWith("Cat ") })
    }

    /**
     * An MP3 and an IMA ADPCM WAV. Headless runs decode sounds without playing them, so "play
     * sound until done" waits for the decoded length on the virtual clock: 0.5 s for the MP3,
     * then 0.37 s for the WAV (whole ADPCM blocks). The pitch effect changes how long the MP3
     * plays, in whole frames of 1/30 s.
     */
    @Test
    fun compilesAndRunsSoundsProject() {
        val result = JScratch.compile(File(samplesDir, "sounds.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 200)
        val expected =
            listOf(
                // the MP3 and the WAV, in tenths of a second
                "5",
                "9",
                // an octave up takes 8 frames, in hundredths, then an octave down and cleared, in tenths
                "27",
                "10",
                "5",
                // set volume and set pan inside "run without screen refresh" wait a frame each
                "2",
                // up half an octave after 9 frames: 200 ms of sound left take 141 ms, so 5 more frames, in hundredths
                "47",
                // after 5 frames, noticed in the next one: stop all sounds, the same sound played again, and a clone playing it
                "20",
                "20",
                "20",
            ).map { "Speaker says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Speaker ") })
    }

    /**
     * Pixel-accurate collision. In every case the bounding boxes overlap (or cross the edge, or
     * cover the mouse), so only the costume pixels, rotation and size decide the answer.
     */
    @Test
    fun compilesAndRunsCollisionProject() {
        val result = JScratch.compile(File(samplesDir, "collision.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 10)
        val expected =
            listOf(
                // circle against circle
                "false",
                "true",
                // rotated bar: vertical, horizontal, diagonal away, diagonal through
                "false",
                "true",
                "false",
                "true",
                // vertical bar at size 50, then 100
                "false",
                "true",
                // edge, then a costume with nothing opaque right next to it
                "false",
                "true",
                "false",
                // mouse
                "false",
                "true",
            ).map { "Probe says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Probe ") })
    }

    /** Color sensing against another sprite, the backdrop, the white stage behind it and the pen layer. */
    @Test
    fun compilesAndRunsColorsProject() {
        val result = JScratch.compile(File(samplesDir, "colors.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 10)
        val expected =
            listOf(
                // the ball: far away, overlapping, then only the purple half of the probe counts
                "false",
                "true",
                "true",
                "false",
                // still touching while hidden
                "true",
                // inside the backdrop's red strip: red but no white, then white with no backdrop
                "true",
                "false",
                "true",
                // a pen line, then the cleared pen layer
                "true",
                "false",
            ).map { "Probe says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Probe ") })
    }

    /**
     * Graphic effects change what a sprite touches, its bounds, its own colors and what it stamps.
     * The Probe square sits just right of the ball, its orange half nearer.
     */
    @Test
    fun compilesAndRunsEffectsProject() {
        val result = JScratch.compile(File(samplesDir, "effects.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 10)
        val expected =
            listOf(
                // touching the probe: plain, fisheye, mosaic, cleared
                "false",
                "true",
                "true",
                "false",
                // touching the edge near it, then with mosaic filling the whole costume box
                "false",
                "true",
                // the ball's blue on the probe's orange, then shifted by the color effect, then black
                "true",
                "false",
                "true",
                "true",
                // a stamp with the color effect, then the cleared pen layer
                "true",
                "false",
            ).map { "Ball says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Ball ") })
    }

    /**
     * Edge-activated hats. The Ball jumps onto the Wall at frames 6 and 18 and off it at frame 12. The
     * timer passes 0.5 s at frame 16, and again 16 frames after the hat resets it. The hats that are true
     * from the start fire once.
     */
    @Test
    fun compilesAndRunsHatsProject() {
        val result = JScratch.compile(File(samplesDir, "hats.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 100)
        val expected =
            listOf(
                "timer was already greater",
                "loudness -1",
                "loud false",
                "touch 1",
                "timer 16",
                "touch 2",
                "timer 16",
            ).map { "Ball says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Ball ") })
    }

    /** Blocks hidden in Scratch 3 that still load from Scratch 2 projects. */
    @Test
    fun compilesAndRunsScratch2Project() {
        val result = JScratch.compile(File(samplesDir, "scratch2.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 30)
        val expected =
            listOf(
                // for each counts from 1 and leaves the variable at the last value
                "each 1",
                "each 2",
                "each 3",
                "after 3",
                "counter 2",
                "all at once",
                // x scroll, y scroll and user id report nothing, and the other old blocks do nothing
                "legacy []",
                "done",
            ).map { "Cat says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Cat ") })
    }

    /**
     * `username` reports the `jscratch.username` property, and nothing without it. Whether the machine is
     * online varies, so the project only says whether `online?` is a boolean.
     */
    @Test
    fun compilesAndRunsSensingProject() {
        val result = JScratch.compile(File(samplesDir, "sensing.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val named = runHeadless(result.jar, frames = 10, "-Djscratch.username=ada")
        assertEquals(
            listOf("Cat says: user: ada", "Cat says: online true"),
            named.lines().filter { it.startsWith("Cat ") },
        )
        val anonymous = runHeadless(result.jar, frames = 10)
        assertEquals(
            listOf("Cat says: user: ", "Cat says: online true"),
            anonymous.lines().filter { it.startsWith("Cat ") },
        )
    }

    /**
     * Monitors from the project file, `show` and `hide` blocks, and a monitor placed automatically. The
     * screenshot is checked at spots whose layout does not depend on the font: the colored fill of each
     * value, or its absence. Coordinates are stage pixels from the top left; the screenshot is twice that.
     */
    @Test
    fun compilesAndRunsMonitorsProject() {
        val result = JScratch.compile(File(samplesDir, "monitors.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val png = Files.createTempFile("jscratch-monitors", ".png").toFile()
        try {
            val out = runHeadless(result.jar, frames = 30, "-Djscratch.screenshot=${png.path}")
            assertEquals(listOf("Cat says: done"), out.lines().filter { it.startsWith("Cat ") })
            val img = ImageIO.read(png)
            val variable = 0xFF8C1A
            val list = 0xFF661A

            // Value pills of default monitors: in the record, shown by a block (placed below the first), hidden.
            assertTrue(img.has(variable, 5, 10, 200, 22), "score monitor")
            assertTrue(img.has(variable, 5, 37, 200, 49), "monitor shown by a block and placed automatically")
            assertFalse(img.has(variable, 0, 300, 240, 330), "monitor hidden by a block")
            // A large readout is filled with the category color, and a slider has a thumb.
            assertEquals(variable, img.at(260, 15), "large monitor")
            assertTrue(img.has(0x0075FF, 250, 78, 480, 102), "slider")
            // Sprite-local variable and built-in reporters, each in its category color.
            assertTrue(img.has(variable, 5, 125, 240, 137), "sprite-local monitor")
            assertTrue(img.has(0x4C97FF, 5, 155, 240, 167), "x position monitor")
            assertTrue(img.has(0x5CB1D6, 5, 185, 240, 197), "timer monitor")
            // The list at (360, 120) has three rows of 24 px below a 21 px header, then empty space.
            for (row in 0 until 3) assertEquals(list, img.at(462, 142 + 24 * row + 12), "list row ${row + 1}")
            assertEquals(0xE5F0FF, img.at(462, 142 + 24 * 3 + 12), "below the last list row")
            assertFalse(img.has(list, 130, 250, 232, 332), "list hidden by a block")
        } finally {
            png.delete()
        }
    }

    /** RGB of the stage pixel at ([x], [y]) in a screenshot taken at twice the stage size. */
    private fun BufferedImage.at(x: Int, y: Int): Int = getRGB(x * 2, y * 2) and 0xFFFFFF

    /** Whether any pixel in the stage rectangle from ([x0], [y0]) to ([x1], [y1]) is exactly [rgb]. */
    private fun BufferedImage.has(rgb: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        for (y in y0 * 2 until y1 * 2) {
            for (x in x0 * 2 until x1 * 2) {
                if (getRGB(x, y) and 0xFFFFFF == rgb) return true
            }
        }
        return false
    }

    private fun runHeadless(jar: ByteArray, frames: Int, vararg properties: String): String {
        val tmp = Files.createTempFile("jscratch-test", ".jar").toFile()
        try {
            tmp.writeBytes(jar)
            val command =
                listOf(java.path, "-Djscratch.headless=$frames", "-Djava.awt.headless=true") + properties +
                    listOf("-jar", tmp.path)
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("compiled project did not finish within 60 s. stderr:\n$stderr")
            }
            if (process.exitValue() != 0) fail("compiled project exited with ${process.exitValue()}. stderr:\n$stderr")
            if (stderr.isNotBlank()) println("child stderr:\n$stderr")
            return stdout
        } finally {
            tmp.delete()
        }
    }
}
