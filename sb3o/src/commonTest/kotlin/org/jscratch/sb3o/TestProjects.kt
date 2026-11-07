package org.jscratch.sb3o

import org.jscratch.sb3o.model.Project
import org.jscratch.sb3o.model.ProjectReader
import org.jscratch.sb3o.model.ProjectWriter
import org.jscratch.sb3o.model.Target
import org.jscratch.sb3o.passes.OptimizeReport

/** Builds the `project.json` of a project with a stage and one sprite called "S" from block JSON. */
internal class ProjectBuilder {
    private val blocks = ArrayList<String>()

    fun block(
        id: String,
        opcode: String,
        next: String? = null,
        parent: String? = null,
        inputs: String = "",
        fields: String = "",
        topLevel: Boolean = false,
        shadow: Boolean = false,
        mutation: String? = null,
        comment: String? = null,
    ): ProjectBuilder {
        val members = ArrayList<String>()
        members.add("\"opcode\": \"$opcode\"")
        members.add("\"next\": ${next.json()}")
        members.add("\"parent\": ${parent.json()}")
        members.add("\"inputs\": {$inputs}")
        members.add("\"fields\": {$fields}")
        members.add("\"shadow\": $shadow")
        members.add("\"topLevel\": $topLevel")
        if (topLevel) members.add("\"x\": 10, \"y\": 20")
        if (mutation != null) members.add("\"mutation\": $mutation")
        if (comment != null) members.add("\"comment\": \"$comment\"")
        blocks.add("\"$id\": {${members.joinToString(", ")}}")
        return this
    }

    /** A hat with a chain of command blocks under it: `block(hat, ids...)`. */
    fun script(hat: String, vararg commands: Pair<String, String>): ProjectBuilder {
        block(hat, "event_whenflagclicked", next = commands.firstOrNull()?.first, topLevel = true)
        var prev = hat
        for ((i, c) in commands.withIndex()) {
            block(c.first, c.second, next = commands.getOrNull(i + 1)?.first, parent = prev)
            prev = c.first
        }
        return this
    }

    /** A custom block definition called [proccode] (with its prototype `<id>_proto`), followed by [next]. */
    fun definition(id: String, proccode: String, next: String? = null): ProjectBuilder {
        block(
            id,
            "procedures_definition",
            next = next,
            topLevel = true,
            inputs = "\"custom_block\": [1, \"${id}_proto\"]",
        )
        block(
            "${id}_proto",
            "procedures_prototype",
            parent = id,
            shadow = true,
            mutation = """{"tagName": "mutation", "children": [], "proccode": "$proccode", "argumentids": "[]",
                "argumentnames": "[]", "argumentdefaults": "[]", "warp": "false"}""",
        )
        return this
    }

    fun call(id: String, proccode: String, next: String? = null, parent: String? = null): ProjectBuilder = block(
        id,
        "procedures_call",
        next = next,
        parent = parent,
        mutation =
        """{"tagName": "mutation", "children": [], "proccode": "$proccode", "argumentids": "[]", "warp": "false"}""",
    )

    fun build(comments: String = ""): String =
        """
        {
          "targets": [
            {"isStage": true, "name": "Stage", "variables": {"v": ["x", 0]}, "lists": {}, "broadcasts": {}, "blocks": {},
             "comments": {}, "currentCostume": 0, "costumes": [], "sounds": [], "volume": 100, "layerOrder": 0},
            {"isStage": false, "name": "S", "variables": {}, "lists": {}, "broadcasts": {},
             "blocks": {${blocks.joinToString(",\n")}},
             "comments": {$comments}, "currentCostume": 0, "costumes": [], "sounds": [], "volume": 100, "layerOrder": 1,
             "visible": true, "x": 0, "y": 0, "size": 100, "direction": 90, "draggable": false, "rotationStyle": "all around"}
          ],
          "monitors": [], "extensions": [], "meta": {"semver": "3.0.0", "vm": "0.0.0", "agent": "test"}
        }
        """.trimIndent()

    private fun String?.json() = if (this == null) "null" else "\"$this\""
}

internal class Optimized(val project: Project, val report: OptimizeReport) {
    val sprite: Target get() = project.sprites.single()

    /** The JSON text of an input of a block of the sprite, as the writer encodes it. */
    fun input(blockId: String, name: String): String? =
        ProjectWriter.write(project)["targets"]!![1]!!["blocks"]!![blockId]?.get("inputs")?.get(name)?.toString()
}

internal fun optimize(builder: ProjectBuilder, options: OptimizeOptions = OptimizeOptions()): Optimized {
    val project = ProjectReader.read(builder.build())
    return Optimized(project, SB3O.optimize(project, options))
}

internal fun lit(type: Int, value: String) = "[1, [$type, \"$value\"]]"

internal fun covered(blockId: String, type: Int, shadowValue: String) = "[3, \"$blockId\", [$type, \"$shadowValue\"]]"
