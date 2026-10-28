package org.jscratch.compiler.jvm

import org.jscratch.compiler.JScratch
import org.jscratch.compiler.codegen.TargetCompiler
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Number literals are folded at compile time, so the compiler's parser has to agree with the
 * runtime's `Cast.parseNumber` (JavaScript `Number()`), or a block would compute something else
 * than the same value read from a variable.
 */
class LiteralFoldingTest {
    private val runtimeJar = File(System.getProperty("jscratch.test.runtimeJar"))
    private val samplesDir = File(System.getProperty("jscratch.test.samplesDir"))
    private val json = RhinoProjectJson()

    @Test
    fun parsesLikeTheRuntime() {
        assertEquals(5.0, TargetCompiler.parseNumber("0b101"))
        assertEquals(15.0, TargetCompiler.parseNumber("0o17"))
        assertEquals(255.0, TargetCompiler.parseNumber(" 0xFF "))
        // Rounded once, like JavaScript, not after every digit.
        assertEquals(1.206901553182527e24, TargetCompiler.parseNumber("0xff9243a8f506b40928b5"))
        assertEquals(-1500.0, TargetCompiler.parseNumber("-1.5e3"))
        assertEquals(Double.NEGATIVE_INFINITY, TargetCompiler.parseNumber("-Infinity"))
        assertEquals(0.0, TargetCompiler.parseNumber(""))
        for (bad in listOf("0x-1", "-0x1", "0x", "0b2", "0x١", "0xａ", "1e", ".", "infinity")) {
            assertTrue(TargetCompiler.parseNumber(bad).isNaN(), "'$bad' should be NaN")
        }
    }

    @Test
    fun onlyDecimalsCountAsDecimal() {
        assertTrue(TargetCompiler.isDecimal("12"))
        assertTrue(TargetCompiler.isDecimal(" -.5e+3 "))
        assertFalse(TargetCompiler.isDecimal(""))
        assertFalse(TargetCompiler.isDecimal("0x10"))
        assertFalse(TargetCompiler.isDecimal("Infinity"))
    }

    /**
     * A prefixed literal is a number where a number is wanted, and stays the text it was typed as
     * where text is wanted, as in Scratch. `say` rounds numbers to hundredths the way scratch-vm does,
     * without clamping huge ones.
     */
    @Test
    fun foldedLiteralsMatchTheBlocks() {
        fun say(id: String, next: String?, message: String) =
            """
            "$id": {"opcode": "looks_say", "next": ${next?.let { "\"$it\"" }}, "parent": null,
                    "inputs": {"MESSAGE": $message}, "fields": {}, "shadow": false, "topLevel": false},
            """

        fun sum(id: String, parent: String, a: String) =
            """
            "$id": {"opcode": "operator_add", "next": null, "parent": "$parent",
                    "inputs": {"NUM1": [1, [4, "$a"]], "NUM2": [1, [4, "0"]]},
                    "fields": {}, "shadow": false, "topLevel": false},
            """
        val blocks =
            """
            "flag": {"opcode": "event_whenflagclicked", "next": "s1", "parent": null, "inputs": {},
                     "fields": {}, "shadow": false, "topLevel": true, "x": 0, "y": 0},
            ${say("s1", "s2", "[1, [4, \"0b101\"]]")}
            ${say("s2", "s3", "[3, \"a2\", [10, \"\"]]")}
            ${sum("a2", "s2", "0b101")}
            ${say("s3", "s4", "[3, \"a3\", [10, \"\"]]")}
            ${sum("a3", "s3", "0o17")}
            ${say("s4", "s5", "[3, \"a4\", [10, \"\"]]")}
            ${sum("a4", "s4", "0x-1")}
            ${say("s5", "s6", "[3, \"a5\", [10, \"\"]]")}
            ${sum("a5", "s5", "0xff9243a8f506b40928b5")}
            ${say("s6", null, "[3, \"a6\", [10, \"\"]]")}
            ${sum("a6", "s6", "-Infinity").trimEnd().removeSuffix(",")}
            """
        val result = JScratch.compile(syntheticSb3(samplesDir, "Py", blocks), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")
        val out = runHeadless(result.jar, frames = 5)
        // Numbers too large to round to hundredths are said as they are, not clamped to a long.
        val expected = listOf("0b101", "5", "15", "0", "1.206901553182527e+24", "-Infinity").map { "Py says: $it" }
        assertEquals(expected, out.lines().filter { it.startsWith("Py ") })
    }
}
