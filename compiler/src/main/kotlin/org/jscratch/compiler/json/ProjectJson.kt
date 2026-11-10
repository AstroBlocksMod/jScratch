package org.jscratch.compiler.json

import org.jscratch.sb3o.json.JsonValue

/**
 * Platform services the compiler needs for JSON. On the JVM, plain JSON goes through
 * kotlinx-serialization and `parseProject` runs scratch-parser inside Rhino. In
 * the browser, JSON goes through `JSON.parse` and `parseProject` calls the same
 * scratch-parser bundle loaded by the page. Both hand back SB3O's [JsonValue] tree.
 */
interface ProjectJson {
    /** Parses arbitrary JSON text. */
    fun parse(text: String): JsonValue

    /**
     * Runs `project.json` text through scratch-parser (JSON parsing plus validation
     * against the official sb3 schema) and returns the project tree. Throws
     * `Sb3Exception` when the text is not a valid Scratch 3 project.
     */
    fun parseProject(text: String): JsonValue
}
