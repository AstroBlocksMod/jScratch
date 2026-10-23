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
