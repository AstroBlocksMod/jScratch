package org.jscratch.compiler.jvm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.JsonArray as TreeArray
import org.jscratch.compiler.json.JsonBoolean as TreeBoolean
import org.jscratch.compiler.json.JsonNull as TreeNull
import org.jscratch.compiler.json.JsonNumber as TreeNumber
import org.jscratch.compiler.json.JsonObject as TreeObject
import org.jscratch.compiler.json.JsonString as TreeString

/** Converts a kotlinx-serialization element into the compiler's JSON tree. */
fun JsonElement.toTree(): JsonValue = when (this) {
    is JsonNull -> TreeNull
    is JsonPrimitive ->
        when {
            isString -> TreeString(content)
            else -> booleanOrNull?.let { TreeBoolean(it) } ?: TreeNumber(doubleOrNull ?: 0.0)
        }
    is JsonArray -> TreeArray(ArrayList(map { it.toTree() }))
    is JsonObject -> {
        val fields = LinkedHashMap<String, JsonValue>(size * 2)
        for ((k, v) in this) fields[k] = v.toTree()
        TreeObject(fields)
    }
}
