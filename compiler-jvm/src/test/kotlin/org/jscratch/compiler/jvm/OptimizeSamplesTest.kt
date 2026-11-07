package org.jscratch.compiler.jvm

import org.jscratch.compiler.CompileOptions
import org.jscratch.compiler.JScratch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end for SB3O through jScratch: a project compiled with `--optimize` has to say exactly what it
 * says without, and the optimize sample shows the passes at work.
 */
class OptimizeSamplesTest {
    private val runtimeJar = File(System.getProperty("jscratch.test.runtimeJar")).readBytes()
    private val samplesDir = File(System.getProperty("jscratch.test.samplesDir"))
    private val json = RhinoProjectJson()

    /** Only the bubbles: the runtime also prints timing and warnings. */
    private fun said(output: String): List<String> = output.lines().filter { " says: " in it || " thinks: " in it }

    /**
     * Every expression in the sample folds (six, counting a nested join once), both branches of the ifs are
     * decided, the loop that ends at once goes, and so do the loose stack and the custom block nothing calls.
     * Numbers stay numbers: `say` rounds 1/3 to 0.33 and 0.1 + 0.2 to 0.3 only because the folded literal is
     * still a number.
     */
    @Test
    fun optimizeSampleSaysTheSameWithFewerBlocks() {
        val sb3 = File(samplesDir, "optimize.sb3").readBytes()
        val plain = JScratch.compile(sb3, runtimeJar, json)
        val optimized = JScratch.compile(sb3, runtimeJar, json, CompileOptions(optimize = true))
        assertEquals(emptyList(), optimized.warnings, "unexpected warnings")
        val expected = listOf("4", "0.33", "abc", "yes", "y", "0.3", "3", "hi Cat", "done").map { "Cat says: $it" }
        assertEquals(expected, said(runHeadless(plain.jar, frames = 10)))
        assertEquals(expected, said(runHeadless(optimized.jar, frames = 10)))

        val report = assertNotNull(optimized.optimization)
        assertEquals(
            listOf(
                "Cat: removed a loose stack of 1 block starting with looks_say",
                "Cat: folded 6 constant expressions",
                "Cat: decided 3 control blocks with a constant condition",
                "Cat: removed the unused custom block \"unused\" (3 blocks)",
            ),
            report.messages,
        )
        val opcodes = optimized.project.sprites.single().blocks.values.map { it.opcode }
        assertTrue(opcodes.none { it.startsWith("control_") }, "left: $opcodes")
        // The join with the custom block's argument is the one operator that cannot fold.
        assertEquals(listOf("operator_join"), opcodes.filter { it.startsWith("operator_") }, "left: $opcodes")
        assertEquals(report.blocksAfter, opcodes.size)
        assertTrue(report.blocksAfter < report.blocksBefore)
    }

    /** The other samples, optimized, say what they say as they are. */
    @Test
    fun everySampleSaysTheSameOptimized() {
        val frames = mapOf("hello" to 40, "sounds" to 200, "monitors" to 30, "legacy" to 30, "hats" to 100)
        val samples = samplesDir.listFiles { f -> f.extension == "sb3" }.orEmpty().sortedBy { it.name }
        assertTrue(samples.size > 5, "samples not found in $samplesDir")
        for (file in samples) {
            val sb3 = file.readBytes()
            val plain = JScratch.compile(sb3, runtimeJar, json)
            val optimized = JScratch.compile(sb3, runtimeJar, json, CompileOptions(optimize = true))
            assertEquals(plain.warnings, optimized.warnings, "${file.name}: warnings")
            val n = frames[file.nameWithoutExtension] ?: 10
            assertEquals(said(runHeadless(plain.jar, n)), said(runHeadless(optimized.jar, n)), "${file.name}: output")
            val report = assertNotNull(optimized.optimization)
            assertTrue(report.blocksAfter <= report.blocksBefore, "${file.name}: ${report.summary()}")
        }
    }
}
