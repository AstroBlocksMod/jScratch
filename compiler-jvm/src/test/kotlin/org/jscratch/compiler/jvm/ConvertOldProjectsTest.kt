package org.jscratch.compiler.jvm

import org.jscratch.compiler.JScratch
import org.jscratch.compiler.convert.OldProject
import org.jscratch.compiler.convert.OldProjects
import org.jscratch.compiler.convert.ProjectFormat
import org.jscratch.compiler.sb3.Sb3Exception
import org.jscratch.compiler.sb3.Sb3Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scratch 1.4 and Scratch 2 projects converted with the Scratch Foundation's code running in
 * Rhino, then compiled and run like any .sb3.
 */
class ConvertOldProjectsTest {
    private val runtimeJar = File(System.getProperty("jscratch.test.runtimeJar"))
    private val samplesDir = File(System.getProperty("jscratch.test.samplesDir"))
    private val oldProjects =
        System.getProperty("jscratch.test.oldProjects").split(File.pathSeparator).map(::File).associateBy { it.name }
    private val json = RhinoProjectJson()
    private val converter = RhinoProjectConverter()

    @Test
    fun convertsCompilesAndRunsScratch2Project() {
        val old = OldProjects.read(oldProjects.getValue("hello.sb2").readBytes(), json)
        assertIs<OldProject.Sb2>(old)
        val converted = OldProjects.toSb3(old, converter)
        assertEquals(ProjectFormat.SB2, converted.from)
        assertEquals(emptyList(), converted.warnings, "unexpected conversion warnings")

        val sb3 = Sb3Parser(json).readSb3(converted.sb3)
        val project = sb3.project
        // Sprites in sprite list order, layers in the order Scratch 2 drew them.
        assertEquals(listOf("Stage", "Cat", "Ball"), project.targets.map { it.name })
        val layers = project.sprites.associate { it.name to it.layerOrder }
        assertTrue(layers.getValue("Ball") < layers.getValue("Cat"), "Ball is drawn behind Cat: $layers")
        assertEquals(listOf("score"), project.stage.variables.values.map { it.name })
        assertEquals(listOf("apple", "banana"), project.stage.lists.values.single().items)
        assertEquals(listOf("data_variable"), project.monitors.map { it.opcode })
        // Assets keep their bytes, so they keep their MD5 names.
        val cat = project.sprites.first { it.name == "Cat" }
        assertEquals(listOf("6ac3a6339b85092fa9b1694f2da7779d.svg"), cat.costumes.map { it.md5ext })
        assertEquals(listOf("fc79d17eb70ef257c9a9f644b2ba1c6e.wav"), cat.sounds.map { it.md5ext })
        // The Scratch 1.4 text layer of the stage's second costume is drawn over its image.
        val title = project.stage.costumes.single { it.name == "Title" }
        assertEquals(
            setOf(
                "e7c147730f19d284bcd7b3f00af19bb6.svg",
                title.md5ext,
                "6ac3a6339b85092fa9b1694f2da7779d.svg",
                "fc79d17eb70ef257c9a9f644b2ba1c6e.wav",
            ),
            sb3.assets.keys,
        )
        val merged = ImageIO.read(ByteArrayInputStream(sb3.assets.getValue(title.md5ext)))
        assertEquals(0xFF0000, merged.getRGB(7, 7) and 0xFFFFFF, "text over the image")
        assertEquals(0x8080FF, merged.getRGB(25, 10) and 0xFFFFFF, "half transparent text over the image")
        assertEquals(0x0000FF, merged.getRGB(35, 25) and 0xFFFFFF, "image without text")

        val result = JScratch.compile(converted.sb3, runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected compile warnings")
        val out = runHeadless(result.jar, frames = 60)
        val expected =
            listOf(
                "Cat says: score: 1",
                "Cat says: score: 2",
                "Cat says: score: 3",
                "Cat says: 49",
                "Cat says: small",
                "Cat says: 3",
                "Cat says: 4",
                "Cat says: cherry",
                "Cat says: 3",
                "Ball says: bye from the ball",
            )
        assertEquals(expected, out.lines().filter { it.startsWith("Cat ") || it.startsWith("Ball ") })
    }

    /**
     * The default project of Scratch 1.4. Its images and sounds are decoded from Squeak's own
     * formats and written as PNG and WAV, so their MD5 names show that Rhino produced the same
     * bytes as the same code does in a browser: the names below come from Node, with the PNG
     * checksum fix from sb3js/build.mjs.
     */
    @Test
    fun convertsAndCompilesScratch14Project() {
        val old = OldProjects.read(oldProjects.getValue("default.sb").readBytes(), json)
        assertIs<OldProject.Sb1>(old)
        val converted = OldProjects.toSb3(old, converter)
        assertEquals(ProjectFormat.SB1, converted.from)
        assertEquals(emptyList(), converted.warnings, "unexpected conversion warnings")

        val sb3 = Sb3Parser(json).readSb3(converted.sb3)
        val project = sb3.project
        assertEquals(listOf("Stage", "Sprite1"), project.targets.map { it.name })
        assertEquals(listOf("848e37e3b408f7b5ac0dce04d31b4d92.png"), project.stage.costumes.map { it.md5ext })
        assertEquals(listOf("83a9787d4cb6f3b7632b4ddfebf74367.wav"), project.stage.sounds.map { it.md5ext })
        val sprite = project.sprites.single()
        assertEquals(
            listOf("d4d79854d4a48c353e081596b0ac2a43.png", "373a4f307b1bd9d89231e4baa3a34c72.png"),
            sprite.costumes.map { it.md5ext },
        )
        assertEquals(listOf("83c36d806dc92327b9e7049a565c6bff.wav"), sprite.sounds.map { it.md5ext })
        for ((name, data) in sb3.assets) {
            assertEquals(name.substringBefore('.'), md5(data), "MD5 of $name")
            // ImageIO checks the zlib checksum that browsers skip (see sb3js/build.mjs).
            if (name.endsWith(".png")) assertNotNull(ImageIO.read(ByteArrayInputStream(data)), name)
        }

        val result = JScratch.compile(converted.sb3, runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected compile warnings")
        runHeadless(result.jar, frames = 2)
    }

    @Test
    fun leavesScratch3ProjectsAlone() {
        assertNull(OldProjects.read(File(samplesDir, "hello.sb3").readBytes(), json))
    }

    @Test
    fun reportsInvalidScratch2Projects() {
        val e =
            assertFailsWith<Sb3Exception> {
                converter.convert(OldProject.Sb2("""{"objName":"Stage","children":[]}""", emptyMap()))
            }
        assertTrue(e.message!!.startsWith("could not convert the Scratch 2 project: "), e.message)
        assertTrue(e.message!!.contains("costumes"), e.message)
    }

    @Test
    fun reportsDamagedScratch14Projects() {
        val e =
            assertFailsWith<Sb3Exception> {
                converter.convert(OldProject.Sb1("ScratchV02 and then nothing".toByteArray()))
            }
        assertTrue(e.message!!.startsWith("could not convert the Scratch 1.4 project: "), e.message)
    }

    private fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data).joinToString("") {
        "%02x".format(it)
    }
}
