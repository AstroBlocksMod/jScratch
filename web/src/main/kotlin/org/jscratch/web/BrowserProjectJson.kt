package org.jscratch.web

import org.jscratch.compiler.json.JsonArray
import org.jscratch.compiler.json.JsonBoolean
import org.jscratch.compiler.json.JsonNull
import org.jscratch.compiler.json.JsonNumber
import org.jscratch.compiler.json.JsonObject
import org.jscratch.compiler.json.JsonString
import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.ProjectJson
import org.jscratch.compiler.sb3.Sb3Exception
import org.teavm.jso.JSBody
import org.teavm.jso.JSObject

/**
 * Browser implementation of [ProjectJson]. JSON parsing is the browser's own
 * `JSON.parse`; `project.json` goes through the scratch-parser bundle the page
 * loads as `jscratch-sb3.js`. The resulting JavaScript objects are walked with
 * a few tiny JSO helpers and copied into the compiler's tree.
 */
object BrowserProjectJson : ProjectJson {
    override fun parse(text: String): JsonValue {
        val value =
            try {
                jsonParse(text)
            } catch (e: Throwable) {
                throw Sb3Exception("invalid JSON: ${e.message}")
            }
        return convert(value)
    }

    override fun parseProject(text: String): JsonValue {
        val value =
            try {
                parseProjectJs(text)
            } catch (e: Throwable) {
                throw Sb3Exception(errorMessage(e))
            }
        return convert(value)
    }

    private fun convert(v: JSObject?): JsonValue {
        if (v == null) return JsonNull
        return when (typeOf(v)) {
            "string" -> JsonString(asString(v))
            "number" -> JsonNumber(asDouble(v))
            "boolean" -> JsonBoolean(asBoolean(v))
            "undefined" -> JsonNull
            else ->
                if (isArray(v)) {
                    val n = length(v)
                    val items = ArrayList<JsonValue>(n)
                    for (i in 0 until n) items.add(convert(at(v, i)))
                    JsonArray(items)
                } else {
                    val keys = keys(v)
                    val n = length(keys)
                    val fields = LinkedHashMap<String, JsonValue>(n * 2)
                    for (i in 0 until n) {
                        val key = asString(at(keys, i)!!)
                        fields[key] = convert(get(v, key))
                    }
                    JsonObject(fields)
                }
        }
    }

    private fun errorMessage(e: Throwable): String = e.message ?: e.toString()
}

@JSBody(params = ["text"], script = "return JSON.parse(text);")
private external fun jsonParse(text: String): JSObject?

@JSBody(params = ["text"], script = "return window.jscratchSb3.parseProject(text);")
private external fun parseProjectJs(text: String): JSObject?

@JSBody(params = ["v"], script = "return typeof v;")
private external fun typeOf(v: JSObject): String

@JSBody(params = ["v"], script = "return Array.isArray(v);")
private external fun isArray(v: JSObject): Boolean

@JSBody(params = ["v"], script = "return v.length;")
private external fun length(v: JSObject): Int

@JSBody(params = ["v", "i"], script = "return v[i];")
private external fun at(v: JSObject, i: Int): JSObject?

@JSBody(params = ["v", "k"], script = "return v[k];")
private external fun get(v: JSObject, k: String): JSObject?

@JSBody(params = ["v"], script = "return Object.keys(v);")
private external fun keys(v: JSObject): JSObject

@JSBody(params = ["v"], script = "return v;")
private external fun asString(v: JSObject): String

@JSBody(params = ["v"], script = "return v;")
private external fun asDouble(v: JSObject): Double

@JSBody(params = ["v"], script = "return v;")
private external fun asBoolean(v: JSObject): Boolean
