package org.jscratch.compiler.convert

import org.jscratch.compiler.Archives
import org.jscratch.compiler.json.ProjectJson

/** The Scratch version a project file was saved by. */
enum class ProjectFormat(val label: String, val extension: String) {
    SB1("Scratch 1.4", "sb"),
    SB2("Scratch 2", "sb2"),
    SB3("Scratch 3", "sb3"),
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
     * Reads a Scratch 1.4 file or a Scratch 2 archive. Returns null for anything else,
     * which is left to the .sb3 parser to accept or reject.
     */
    fun read(bytes: ByteArray, json: ProjectJson): OldProject? {
        if (isSb1(bytes)) return OldProject.Sb1(bytes)
        if (!Archives.isZip(bytes)) return null
        val files = Archives.read(bytes)
        val projectJson = files.remove("project.json")?.toString(Charsets.UTF_8) ?: return null
        val root =
            try {
                json.parse(projectJson)
            } catch (e: Exception) {
                return null
            }
        // Scratch 3 keeps its sprites in "targets"; Scratch 2 nests them under the stage.
        if (root["targets"]?.asArray != null || root["objName"] == null) return null
        return OldProject.Sb2(projectJson, files)
    }

    /** Converts [project] with [converter] and packs the result into an .sb3 archive. */
    fun toSb3(project: OldProject, converter: ProjectConverter): ConvertResult {
        val converted = converter.convert(project)
        val sb3 = Archives.writeProject(converted.projectJson.toByteArray(Charsets.UTF_8), converted.assets)
        return ConvertResult(sb3, project.format, converted.warnings)
    }

    /** Scratch 1.4 files start with "ScratchV01" or "ScratchV02". */
    private fun isSb1(bytes: ByteArray): Boolean {
        val magic = "ScratchV0"
        if (bytes.size < magic.length) return false
        for (i in magic.indices) {
            if (bytes[i] != magic[i].code.toByte()) return false
        }
        return true
    }
}

/** The result of [OldProjects.toSb3]: the .sb3 archive and anything the converter warned about. */
class ConvertResult(val sb3: ByteArray, val from: ProjectFormat, val warnings: List<String>)
