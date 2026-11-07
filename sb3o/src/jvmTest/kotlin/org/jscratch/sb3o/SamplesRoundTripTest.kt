package org.jscratch.sb3o

import org.jscratch.sb3o.json.JsonParser
import org.jscratch.sb3o.model.ProjectReader
import org.jscratch.sb3o.model.ProjectWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every sample project (`samples/src/<name>/project.json`) reads, writes and reads again without
 * losing anything. The samples are written by hand and leave out members the editor always
 * writes, such as `md5ext`, which the writer fills in, so the check is that the original is
 * contained in the output, and that writing the output again gives the same text.
 */
class SamplesRoundTripTest {
    private val samplesSrc = File(System.getProperty("sb3o.test.samplesSrc"))

    @Test
    fun samplesSurviveAReadAndWrite() {
        val projects = samplesSrc.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        assertTrue(projects.isNotEmpty(), "no samples found in $samplesSrc")
        for (dir in projects) {
            val text = File(dir, "project.json").readText()
            val original = JsonParser.parse(text)
            val written = ProjectWriter.write(ProjectReader.read(original))
            assertContains(original, written, dir.name)
            val again = ProjectWriter.write(ProjectReader.read(written))
            assertEquals(written, again, "${dir.name}: writing the output again should change nothing")
        }
    }
}
