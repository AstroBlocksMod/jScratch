package org.jscratch.compiler.jvm

import org.jscratch.compiler.JScratch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scripts with very long block chains are split into several methods, each small enough for
 * HotSpot to compile (see `TargetCompiler.SPLIT_BUDGET`). The project here is built by hand: a
 * 1200-block script, and a warp custom block whose 600-block body uses its argument and then
 * stops the script, so the test covers argument passing into the helper methods and a stop that
 * has to travel back out of one.
 */
class SplitScriptsTest {
    private val runtimeJar = File(System.getProperty("jscratch.test.runtimeJar"))
    private val samplesDir = File(System.getProperty("jscratch.test.samplesDir"))
    private val json = RhinoProjectJson()

    @Test
    fun longScriptsSplitAndStillBehave() {
        val result = JScratch.compile(longProject(), runtimeJar.readBytes(), json)
        assertEquals(emptyList(), result.warnings, "unexpected warnings")

        val listing = JScratch.disassemble(result.classes.getValue("jscratch/project/Sprite_Py"))
        assertTrue(listing.contains("script_0$0()Z"), "the long script should spill into helper methods")
        assertTrue(
            listing.contains("proc_bump_s$0(Ljava/lang/Object;)Z"),
            "the long procedure should spill into helpers that take its argument",
        )
        for ((name, lines) in methodSizes(listing)) {
            assertTrue(lines < 2000, "method $name has $lines instruction lines, past what HotSpot would compile")
        }

        val out = runHeadless(result.jar, frames = 40)
        // n counts the 1200-block chain, m the 600 "change m by x" of `bump 7`, stopped before the final +1.
        assertEquals(listOf("Py says: 1200/4200"), out.lines().filter { it.startsWith("Py ") })
    }

    /**
     * A corrupt project can link a chain into a cycle. The cycle is emitted once, as before splitting,
     * even when it is long enough to span several helper methods.
     */
    @Test
    fun aCycleSpanningHelpersIsEmittedOnce() {
        val blocks = StringBuilder()
        blocks.append(
            """
            "flag": {"opcode": "event_whenflagclicked", "next": "k0", "parent": null, "inputs": {},
                     "fields": {}, "shadow": false, "topLevel": true, "x": 0, "y": 0},
            "flag2": {"opcode": "event_whenflagclicked", "next": "pause", "parent": null, "inputs": {},
                      "fields": {}, "shadow": false, "topLevel": true, "x": 0, "y": 0},
            "pause": {"opcode": "control_wait", "next": "sayit", "parent": null,
                      "inputs": {"DURATION": [1, [5, "0.1"]]}, "fields": {}, "shadow": false, "topLevel": false},
            "sayit": {"opcode": "looks_say", "next": null, "parent": null,
                      "inputs": {"MESSAGE": [3, [12, "n", "v_n"], [10, ""]]}, "fields": {},
                      "shadow": false, "topLevel": false},
            """,
        )
        // 600 blocks whose last one links back to the first.
        val cycle = 600
        for (i in 0 until cycle) {
            val sep = if (i == cycle - 1) "" else ","
            blocks.append(
                """
                "k$i": {"opcode": "data_changevariableby", "next": "k${(i + 1) % cycle}", "parent": null,
                        "inputs": {"VALUE": [1, [4, "1"]]}, "fields": {"VARIABLE": ["n", "v_n"]},
                        "shadow": false, "topLevel": false}$sep
                """,
            )
        }
        val sb3 = syntheticSb3(samplesDir, "Py", blocks.toString(), variables = mapOf("n" to 0))
        val result = JScratch.compile(sb3, runtimeJar.readBytes(), json)
        val listing = JScratch.disassemble(result.classes.getValue("jscratch/project/Sprite_Py"))
        val helpers = Regex("""script_\d+\$\d+\(""").findAll(listing).map { it.value }.toSet()
        assertTrue(helpers.size in 1..8, "expected a few helpers for 600 blocks, got ${helpers.size}")

        val out = runHeadless(result.jar, frames = 10)
        assertEquals(listOf("Py says: 600"), out.lines().filter { it.startsWith("Py ") })
    }

    /** Lines of each method body in a Textifier listing, a rough stand-in for bytecode bytes. */
    private fun methodSizes(listing: String): Map<String, Int> {
        val sizes = LinkedHashMap<String, Int>()
        var method: String? = null
        var count = 0
        for (line in listing.lines()) {
            val header = Regex("""^  (?:private|public|protected| )*.*\((.*)\)""").matchEntire(line)
            when {
                header != null && !line.contains("//") -> {
                    method?.let { sizes[it] = count }
                    method = line.trim()
                    count = 0
                }
                line.isBlank() -> {
                    method?.let { sizes[it] = count }
                    method = null
                }
                else -> count++
            }
        }
        return sizes
    }

    private fun longProject(): ByteArray =
        syntheticSb3(samplesDir, "Py", longBlocks(), variables = mapOf("n" to 0, "m" to 0))

    private fun longBlocks(): String {
        val blocks = StringBuilder()
        blocks.append(
            """
            "flag": {"opcode": "event_whenflagclicked", "next": "c0", "parent": null, "inputs": {},
                     "fields": {}, "shadow": false, "topLevel": true, "x": 0, "y": 0},
            """,
        )
        // The 1200-block chain: change n by 1, over and over.
        val chain = 1200
        for (i in 0 until chain) {
            val next = if (i == chain - 1) "call" else "c${i + 1}"
            blocks.append(
                """
                "c$i": {"opcode": "data_changevariableby", "next": "$next", "parent": null,
                        "inputs": {"VALUE": [1, [4, "1"]]}, "fields": {"VARIABLE": ["n", "v_n"]},
                        "shadow": false, "topLevel": false},
                """,
            )
        }
        blocks.append(
            """
            "call": {"opcode": "procedures_call", "next": "sayit", "parent": null,
                     "inputs": {"a1": [1, [4, "7"]]}, "fields": {}, "shadow": false, "topLevel": false,
                     "mutation": {"tagName": "mutation", "children": [], "proccode": "bump %s",
                                  "argumentids": "[\"a1\"]", "warp": "true"}},
            "sayit": {"opcode": "looks_say", "next": null, "parent": null,
                      "inputs": {"MESSAGE": [3, "joined", [10, ""]]}, "fields": {},
                      "shadow": false, "topLevel": false},
            "joined": {"opcode": "operator_join", "next": null, "parent": "sayit",
                       "inputs": {"STRING1": [3, [12, "n", "v_n"], [10, ""]],
                                  "STRING2": [3, "joined2", [10, ""]]},
                       "fields": {}, "shadow": false, "topLevel": false},
            "joined2": {"opcode": "operator_join", "next": null, "parent": "joined",
                        "inputs": {"STRING1": [1, [10, "/"]],
                                   "STRING2": [3, [12, "m", "v_m"], [10, ""]]},
                        "fields": {}, "shadow": false, "topLevel": false},
            "def": {"opcode": "procedures_definition", "next": "p0", "parent": null,
                    "inputs": {"custom_block": [1, "proto"]}, "fields": {}, "shadow": false,
                    "topLevel": true, "x": 0, "y": 0},
            "proto": {"opcode": "procedures_prototype", "next": null, "parent": "def",
                      "inputs": {"a1": [1, "argshadow"]}, "fields": {}, "shadow": true, "topLevel": false,
                      "mutation": {"tagName": "mutation", "children": [], "proccode": "bump %s",
                                   "argumentids": "[\"a1\"]", "argumentnames": "[\"x\"]",
                                   "argumentdefaults": "[\"1\"]", "warp": "true"}},
            "argshadow": {"opcode": "argument_reporter_string_number", "next": null, "parent": "proto",
                          "inputs": {}, "fields": {"VALUE": ["x", null]}, "shadow": true, "topLevel": false},
            """,
        )
        // The procedure body: change m by x, 600 times, then stop this script before one last change.
        val body = 600
        for (i in 0 until body) {
            val next = if (i == body - 1) "halt" else "p${i + 1}"
            blocks.append(
                """
                "p$i": {"opcode": "data_changevariableby", "next": "$next", "parent": null,
                        "inputs": {"VALUE": [3, "arg$i", [4, "1"]]}, "fields": {"VARIABLE": ["m", "v_m"]},
                        "shadow": false, "topLevel": false},
                "arg$i": {"opcode": "argument_reporter_string_number", "next": null, "parent": "p$i",
                          "inputs": {}, "fields": {"VALUE": ["x", null]}, "shadow": false, "topLevel": false},
                """,
            )
        }
        blocks.append(
            """
            "halt": {"opcode": "control_stop", "next": "after", "parent": null, "inputs": {},
                     "fields": {"STOP_OPTION": ["this script", null]}, "shadow": false, "topLevel": false,
                     "mutation": {"tagName": "mutation", "children": [], "hasnext": "true"}},
            "after": {"opcode": "data_changevariableby", "next": null, "parent": null,
                      "inputs": {"VALUE": [1, [4, "1"]]}, "fields": {"VARIABLE": ["m", "v_m"]},
                      "shadow": false, "topLevel": false}
            """,
        )
        return blocks.toString()
    }
}
