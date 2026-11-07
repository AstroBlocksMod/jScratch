package org.jscratch.sb3o

import org.jscratch.sb3o.json.JsonArray
import org.jscratch.sb3o.json.JsonBoolean
import org.jscratch.sb3o.json.JsonException
import org.jscratch.sb3o.json.JsonNull
import org.jscratch.sb3o.json.JsonNumber
import org.jscratch.sb3o.json.JsonObject
import org.jscratch.sb3o.json.JsonParser
import org.jscratch.sb3o.json.JsonString
import org.jscratch.sb3o.json.JsonWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun parsesEveryKindOfValue() {
        val tree = JsonParser.parse(""" {"a": [1, -2.5e3, "x\nA😀", true, false, null], "b": {}} """)
        val expected =
            JsonObject(
                linkedMapOf(
                    "a" to
                        JsonArray(
                            JsonNumber(1.0),
                            JsonNumber(-2500.0),
                            JsonString("x\nA😀"),
                            JsonBoolean(true),
                            JsonBoolean(false),
                            JsonNull,
                        ),
                    "b" to JsonObject(),
                ),
            )
        assertEquals(expected, tree)
    }

    @Test
    fun rejectsBrokenJson() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "01", "1.", "\"abc", "tru", "{} x", "\"\u0001\"")) {
            assertFailsWith<JsonException>("'$bad' should not parse") { JsonParser.parse(bad) }
        }
    }

    @Test
    fun writesLikeJsonStringify() {
        val value =
            JsonObject(
                linkedMapOf(
                    "n" to JsonArray(JsonNumber(5.0), JsonNumber(0.1), JsonNumber(1e21), JsonNumber(-0.0)),
                    "bad" to JsonArray(JsonNumber(Double.NaN), JsonNumber(Double.POSITIVE_INFINITY)),
                    "s" to JsonString("a\"b\\c\n\t\u0001\u007fé😀"),
                    // Made at run time: a lone surrogate in a source literal does not survive the JS build.
                    "lone" to JsonString(Char(0xD83D).toString()),
                    "e" to JsonObject(),
                    "z" to JsonNull,
                ),
            )
        // Only the characters below space are escaped: DEL and everything beyond ASCII stay as they are.
        val expected =
            """{"n":[5,0.1,1e+21,0],"bad":[null,null],"s":"a\"b\\c\n\t\u0001""" + "\u007f" +
                """é😀","lone":"\ud83d","e":{},"z":null}"""
        assertEquals(expected, JsonWriter.write(value))
    }

    @Test
    fun roundTrips() {
        val text =
            """{"targets":[{"name":"Stage","blocks":{"a":{"opcode":"x","inputs":{"N":[1,[4,"3"]]}}}}],""" +
                """"meta":{"semver":"3.0.0"}}"""
        assertEquals(text, JsonWriter.write(JsonParser.parse(text)))
    }
}
