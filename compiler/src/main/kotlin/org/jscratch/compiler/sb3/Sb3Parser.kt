package org.jscratch.compiler.sb3

import org.jscratch.compiler.json.ProjectJson
import org.jscratch.sb3o.Archives
import org.jscratch.sb3o.json.JsonValue
import org.jscratch.sb3o.model.Project
import org.jscratch.sb3o.model.ProjectReader
import org.jscratch.sb3o.model.Sb3Exception

/** An unpacked .sb3: the parsed project plus every other zip entry (costumes, sounds). */
class Sb3File(val project: Project, val assets: Map<String, ByteArray>)

/**
 * Reads .sb3 archives into the [Project] model.
 *
 * The archive is unzipped by [Archives] with `java.util.zip`, which works both on
 * the JVM and under TeaVM. `project.json` is then handed to scratch-parser, the Scratch
 * Foundation's JavaScript parser, through [ProjectJson]; that parses and validates
 * it against the official sb3 schema. SB3O's [ProjectReader] maps the resulting tree
 * onto the model.
 */
class Sb3Parser(private val json: ProjectJson) {
    fun readSb3(bytes: ByteArray): Sb3File {
        val entries = Archives.read(bytes)
        val projectJson = entries.remove("project.json") ?: throw Sb3Exception("project.json not found in archive")
        val project = parseProject(json.parseProject(projectJson.toString(Charsets.UTF_8)))
        return Sb3File(project, entries)
    }

    fun parseProject(root: JsonValue): Project = ProjectReader.read(root)
}
