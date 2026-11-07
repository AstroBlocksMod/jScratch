package org.jscratch.sb3o.json

import org.jscratch.sb3o.values.JsNumbers

/**
 * A plain JSON tree. [JsonParser] builds it from text and [JsonWriter] turns it back into text,
 * but a host can also build it from its own parser (jScratch feeds it the tree that scratch-parser
 * validated). Values compare structurally, so two trees are equal when their content is, whatever
 * the order of the keys.
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

    /** Text form, with numbers printed the way Scratch (that is, JavaScript) prints them. */
    fun toText(): String = when (this) {
        is JsonString -> value
        is JsonNumber -> JsNumbers.toString(value)
        is JsonBoolean -> value.toString()
        else -> ""
    }

    /** The JSON text of this value, compact. */
    override fun toString(): String = JsonWriter.write(this)
}

data class JsonObject(val fields: LinkedHashMap<String, JsonValue>) : JsonValue() {
    constructor() : this(LinkedHashMap())

    override val asObject: JsonObject get() = this

    operator fun set(key: String, value: JsonValue) {
        fields[key] = value
    }

    override fun toString(): String = JsonWriter.write(this)
}

data class JsonArray(val items: ArrayList<JsonValue>) : JsonValue() {
    constructor(vararg values: JsonValue) : this(arrayListOf(*values))

    override val asArray: JsonArray get() = this
    val size: Int get() = items.size

    override fun toString(): String = JsonWriter.write(this)
}

data class JsonString(val value: String) : JsonValue() {
    override val asString: String get() = value

    override fun toString(): String = JsonWriter.write(this)
}

data class JsonNumber(val value: Double) : JsonValue() {
    override val asDouble: Double get() = value

    override fun toString(): String = JsonWriter.write(this)
}

data class JsonBoolean(val value: Boolean) : JsonValue() {
    override val asBoolean: Boolean get() = value

    override fun toString(): String = JsonWriter.write(this)
}

object JsonNull : JsonValue() {
    override fun toString(): String = "null"
}

/** A Scratch value (Double, String or Boolean) as a JSON value. Anything else becomes its text. */
fun scratchValueToJson(value: Any?): JsonValue = when (value) {
    null -> JsonNull
    is Double -> JsonNumber(value)
    is Number -> JsonNumber(value.toDouble())
    is Boolean -> JsonBoolean(value)
    is String -> JsonString(value)
    else -> JsonString(value.toString())
}
