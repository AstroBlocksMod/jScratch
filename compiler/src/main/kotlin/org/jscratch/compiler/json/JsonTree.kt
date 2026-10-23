package org.jscratch.compiler.json

/**
 * A plain JSON tree. The compiler core never parses JSON itself: the JVM side
 * builds this tree from kotlinx-serialization, the browser side from a
 * `JSON.parse` result, both through [ProjectJson].
 */
sealed class JsonValue {
    open val asObject: JsonObject? get() = null
    open val asArray: JsonArray? get() = null
    open val asString: String? get() = null
    open val asDouble: Double? get() = null
    open val asBoolean: Boolean? get() = null
    val isNull: Boolean get() = this is JsonNull

    operator fun get(key: String): JsonValue? = asObject?.fields?.get(key)

    operator fun get(index: Int): JsonValue? = asArray?.items?.getOrNull(index)

    /** The value as a Scratch value: Double, String or Boolean. Null and containers become "". */
    fun toScratchValue(): Any = when (this) {
        is JsonNumber -> value
        is JsonString -> value
        is JsonBoolean -> value
        else -> ""
    }

    /** Text form, with numbers printed the way Scratch prints them. */
    fun toText(): String = when (this) {
        is JsonString -> value
        is JsonNumber -> numberText(value)
        is JsonBoolean -> value.toString()
        else -> ""
    }

    private fun numberText(n: Double): String {
        val isInteger = n == Math.rint(n) && Math.abs(n) < 1e15
        return if (isInteger) n.toLong().toString() else n.toString()
    }
}

class JsonObject(val fields: LinkedHashMap<String, JsonValue>) : JsonValue() {
    override val asObject: JsonObject get() = this
}

class JsonArray(val items: ArrayList<JsonValue>) : JsonValue() {
    override val asArray: JsonArray get() = this
    val size: Int get() = items.size
}

class JsonString(val value: String) : JsonValue() {
    override val asString: String get() = value
}

class JsonNumber(val value: Double) : JsonValue() {
    override val asDouble: Double get() = value
}

class JsonBoolean(val value: Boolean) : JsonValue() {
    override val asBoolean: Boolean get() = value
}

object JsonNull : JsonValue()

/**
 * Platform services the parser needs. On the JVM, JSON goes through
 * kotlinx-serialization and `parseProject` runs scratch-parser inside Rhino. In
 * the browser, JSON goes through `JSON.parse` and `parseProject` calls the same
 * scratch-parser bundle loaded by the page.
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
