package org.jscratch.compiler.jvm

import org.jscratch.compiler.JScratch
import org.jscratch.compiler.sb3.Sb3Parser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * then 0.37 s for the WAV (whole ADPCM blocks).
     */
    @Test
    fun compilesAndRunsSoundsProject() {
        val result = JScratch.compile(File(samplesDir, "sounds.sb3").readBytes(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val out = runHeadless(result.jar, frames = 60)
        assertEquals(listOf("Speaker says: 5", "Speaker says: 9"), out.lines().filter { it.startsWith("Speaker ") })
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

    private fun runHeadless(jar: ByteArray, frames: Int): String {
        val tmp = Files.createTempFile("jscratch-test", ".jar").toFile()
        try {
            tmp.writeBytes(jar)
            val process =
                ProcessBuilder(java.path, "-Djscratch.headless=$frames", "-Djava.awt.headless=true", "-jar", tmp.path)
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
