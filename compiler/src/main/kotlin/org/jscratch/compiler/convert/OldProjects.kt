package org.jscratch.compiler.convert

import org.jscratch.compiler.Archives
import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.ProjectJson
import org.jscratch.compiler.sb3.Sb3Exception
import java.io.IOException

/** The Scratch version a project file was saved by. */
enum class ProjectFormat(val label: String) {
    SB1("Scratch 1.4"),
    SB2("Scratch 2"),
    SB3("Scratch 3"),
}

/** A project saved by Scratch 1.4 or Scratch 2, ready for a [ProjectConverter]. */
sealed class OldProject {
    abstract val format: ProjectFormat

    /** The whole .sb file, a serialized Squeak object graph. */
    class Sb1(val data: ByteArray) : OldProject() {
        override val format get() = ProjectFormat.SB1
    }

    /**
     * The `project.json` of an .sb2 archive and its other files. An archive from the
     * Scratch 2 editor names its assets by index (`0.svg`, `1.wav`). A project from the
     * Scratch server comes without assets, so those are fetched and named by MD5 instead.
     */
    class Sb2(val projectJson: String, val files: Map<String, ByteArray>) : OldProject() {
        override val format get() = ProjectFormat.SB2
    }
}

/** A converted project: the Scratch 3 `project.json` and the asset files that go next to it. */
class ConvertedProject(val projectJson: String, val assets: Map<String, ByteArray>, val warnings: List<String>)

/**
 * Converts Scratch 1.4 and Scratch 2 projects to Scratch 3 with the Scratch Foundation's own
 * code (scratch-sb1-converter, and scratch-vm's sb2 and sb3 serializers), bundled by the
 * `sb3js` module as `jscratch-convert.js`. The JVM runs it in Rhino; the browser playground
 * calls the bundle from the page, because it returns a promise.
 */
fun interface ProjectConverter {
    /** Throws `Sb3Exception` when the project cannot be converted. */
    fun convert(project: OldProject): ConvertedProject
}

object OldProjects {
    /**
     * Which Scratch saved a project file, or null when it does not look like a Scratch project.
     * Only `project.json` is inflated. A `project.json` that is not plain JSON also gives null,
     * because scratch-parser cleans up some of those, so the .sb3 parser should still see it.
     * Throws `Sb3Exception` for a damaged archive.
     */
    fun detect(bytes: ByteArray, json: ProjectJson): ProjectFormat? {
        if (isSb1(bytes)) return ProjectFormat.SB1
        if (!Archives.isZip(bytes)) return null
        val projectJson = unzip { Archives.read(bytes, "project.json") } ?: return null
        val root =
            try {
                json.parse(projectJson.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                return null
            }
        return format(root)
    }

    /**
     * Which Scratch wrote a `project.json`, or null when neither did. Scratch 3 keeps its
     * sprites in "targets"; Scratch 2 nests them under the stage.
     */
    fun format(root: JsonValue): ProjectFormat? = when {
        root["targets"]?.asArray != null -> ProjectFormat.SB3
        root["objName"] != null -> ProjectFormat.SB2
        else -> null
    }

    /**
     * Reads a Scratch 1.4 file or a Scratch 2 archive. Returns null for anything else, which
     * is left to the .sb3 parser to accept or reject. Throws `Sb3Exception` for a damaged archive.
     */
    fun read(bytes: ByteArray, json: ProjectJson): OldProject? = when (detect(bytes, json)) {
        ProjectFormat.SB1 -> OldProject.Sb1(bytes)
        ProjectFormat.SB2 -> {
            val files = unzip { Archives.read(bytes) }
            OldProject.Sb2(files.remove("project.json")!!.toString(Charsets.UTF_8), files)
        }
        else -> null
    }

    /** Converts [project] with [converter] and packs the result into an .sb3 archive. */
    fun toSb3(project: OldProject, converter: ProjectConverter): ConvertResult {
        val converted = converter.convert(project)
        val sb3 = Archives.writeProject(converted.projectJson.toByteArray(Charsets.UTF_8), converted.assets)
        return ConvertResult(sb3, converted.warnings)
    }

    /** True for a Scratch 1.4 file or a zip archive, as opposed to bare `project.json`. */
    fun isFile(bytes: ByteArray): Boolean = isSb1(bytes) || Archives.isZip(bytes)

    /** Scratch 1.4 files start with "ScratchV01" or "ScratchV02". */
    private fun isSb1(bytes: ByteArray): Boolean {
        val magic = "ScratchV0"
        if (bytes.size < magic.length) return false
        for (i in magic.indices) {
            if (bytes[i] != magic[i].code.toByte()) return false
        }
        return true
    }

    private inline fun <T> unzip(read: () -> T): T = try {
        read()
    } catch (e: IOException) {
        throw Sb3Exception("the project archive is damaged: ${e.message ?: e.javaClass.simpleName}")
    }
}

/** The result of [OldProjects.toSb3]: the .sb3 archive and anything the converter warned about. */
class ConvertResult(val sb3: ByteArray, val warnings: List<String>)
