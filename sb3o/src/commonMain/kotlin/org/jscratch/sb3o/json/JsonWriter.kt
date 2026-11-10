package org.jscratch.sb3o.json

import org.jscratch.sb3o.values.JsNumbers

/**
 * Writes a [JsonValue] as compact JSON text, the way `JSON.stringify` does: numbers in
 * JavaScript's notation, non-finite numbers as `null`, and only the characters that need it
 * escaped.
 */
object JsonWriter {
    fun write(value: JsonValue): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonBoolean -> out.append(if (value.value) "true" else "false")
            is JsonNumber -> out.append(if (value.value.isFinite()) JsNumbers.toString(value.value) else "null")
            is JsonString -> writeString(value.value, out)
            is JsonArray -> {
                out.append('[')
                var first = true
                for (item in value.items) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        var start = 0
        for (i in s.indices) {
            val c = s[i]
            val escape =
                when {
                    c == '"' -> "\\\""
                    c == '\\' -> "\\\\"
                    c == '\n' -> "\\n"
                    c == '\r' -> "\\r"
                    c == '\t' -> "\\t"
                    c == '\b' -> "\\b"
                    c == '\u000C' -> "\\f"
                    c < ' ' || c.isLoneSurrogate(s, i) -> "\\u" + c.code.toString(16).padStart(4, '0')
                    else -> continue
                }
            out.append(s, start, i).append(escape)
            start = i + 1
        }
        out.append(s, start, s.length).append('"')
    }

    /** `JSON.stringify` escapes surrogates that do not form a pair, so the output is always valid UTF-8. */
    private fun Char.isLoneSurrogate(s: String, i: Int): Boolean = when {
        isHighSurrogate() -> i + 1 >= s.length || !s[i + 1].isLowSurrogate()
        isLowSurrogate() -> i == 0 || !s[i - 1].isHighSurrogate()
        else -> false
    }
}
