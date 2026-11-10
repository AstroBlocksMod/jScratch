package org.jscratch.sb3o.json

class JsonException(message: String) : RuntimeException(message)

/**
 * A small strict JSON parser (RFC 8259) that works on every platform. Numbers become doubles, as
 * `JSON.parse` makes them, so a project reads the same here as in Scratch.
 */
object JsonParser {
    /** How deep objects and arrays may nest before the input is rejected. */
    const val MAX_DEPTH = 512

    fun parse(text: String): JsonValue {
        val p = State(text)
        p.skipWhitespace()
        val value = p.readValue()
        p.skipWhitespace()
        if (p.pos != text.length) p.fail("unexpected text after the JSON value")
        return value
    }

    private class State(val text: String) {
        var pos = 0
        private var depth = 0

        /**
         * Parses an object or array one level deeper. The parser recurses per level, so a hostile file
         * nested a hundred thousand deep would overflow the stack; real projects nest about ten deep.
         */
        private inline fun <T> nested(read: () -> T): T {
            if (++depth > MAX_DEPTH) fail("JSON nested more than $MAX_DEPTH levels deep")
            try {
                return read()
            } finally {
                depth--
            }
        }

        fun fail(message: String): Nothing = throw JsonException("$message at offset $pos")

        fun skipWhitespace() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun readValue(): JsonValue {
            if (pos >= text.length) fail("unexpected end of JSON")
            return when (val c = text[pos]) {
                '{' -> nested { readObject() }
                '[' -> nested { readArray() }
                '"' -> JsonString(readString())
                't' -> literal("true", JsonBoolean(true))
                'f' -> literal("false", JsonBoolean(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) fail("unexpected character '${text[pos]}'")
            pos += word.length
            return value
        }

        private fun readObject(): JsonObject {
            pos++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                pos++
                skipWhitespace()
                fields[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonObject(fields)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): JsonArray {
            pos++
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArray(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else fail("unexpected end of JSON")

        private fun readString(): String {
            pos++
            var start = pos
            var out: StringBuilder? = null
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val c = text[pos]
                when {
                    c == '"' -> {
                        val tail = text.substring(start, pos)
                        pos++
                        return if (out == null) tail else out.append(tail).toString()
                    }
                    c == '\\' -> {
                        val sb = out ?: StringBuilder().also { out = it }
                        sb.append(text, start, pos)
                        pos++
                        if (pos >= text.length) fail("unterminated string")
                        when (val e = text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 5 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                // Exactly four hex digits: toIntOrNull alone would also take a sign.
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail("bad \\u escape '$hex'")
                                }
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                        pos++
                        start = pos
                    }
                    c < ' ' -> fail("control character in string")
                    else -> pos++
                }
            }
        }

        private fun readNumber(): JsonNumber {
            val start = pos
            if (text[pos] == '-') pos++
            if (pos >= text.length) fail("bad number")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                while (pos < text.length && text[pos] in '0'..'9') pos++
            } else {
                fail("bad number")
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail("bad number")
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail("bad number")
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            return JsonNumber(text.substring(start, pos).toDouble())
        }
    }
}
