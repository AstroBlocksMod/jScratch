package org.jscratch.compiler.jvm

import org.jscratch.compiler.codegen.Monitors
import org.jscratch.compiler.codegen.ProjectCompiler
import org.jscratch.compiler.sb3.Sb3Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The monitor table the compiler writes, and the warnings for monitors it has to drop. */
class MonitorTableTest {
    private val json = RhinoProjectJson()

    private fun monitor(id: String, opcode: String, params: String, spriteName: String?, visible: Boolean) =
        """{"id":"$id","mode":"default","opcode":"$opcode","params":{$params},
        "spriteName":${spriteName?.let { "\"$it\"" } ?: "null"},"value":0,"width":0,"height":0,"x":5,"y":5,
        "visible":$visible}"""

    private val costume =
        """{"name":"c","dataFormat":"svg","assetId":"0123456789abcdef0123456789abcdef",
        "md5ext":"0123456789abcdef0123456789abcdef.svg","rotationCenterX":0,"rotationCenterY":0}"""

    private val project =
        """
        {"targets":[
          {"isStage":true,"name":"Stage","variables":{"v_a":["a",1]},"lists":{},"broadcasts":{},"blocks":{},
           "currentCostume":0,"costumes":[$costume],"sounds":[],"volume":100,"layerOrder":0},
          {"isStage":false,"name":"Cat","variables":{},"lists":{},"broadcasts":{},
           "blocks":{
             "hat":{"opcode":"event_whenflagclicked","next":"show","parent":null,"inputs":{},"fields":{},
                    "shadow":false,"topLevel":true,"x":0,"y":0},
             "show":{"opcode":"data_showvariable","next":null,"parent":"hat","inputs":{},
                     "fields":{"VARIABLE":["foo","undeclared"]},"shadow":false,"topLevel":false}},
           "currentCostume":0,"costumes":[$costume],"sounds":[],"volume":100,"layerOrder":1,"visible":true,
           "x":0,"y":0,"size":100,"direction":90,"draggable":false,"rotationStyle":"all around"}],
         "monitors":[
           ${monitor("v_a", "data_variable", "\"VARIABLE\":\"a\"", "Ghost", true)},
           ${monitor("gone", "data_variable", "\"VARIABLE\":\"nothing\"", null, true)},
           ${monitor("tempo", "music_getTempo", "", null, true)},
           ${monitor("quiet", "music_getTempo", "", null, false)}],
         "extensions":[],"meta":{"semver":"3.0.0","vm":"0.2.0","agent":"test"}}
        """.trimIndent()

    @Test
    fun warnsWithTheReasonAMonitorIsDropped() {
        val generated = ProjectCompiler.compile(Sb3Parser(json).parseProject(json.parseProject(project)))
        val monitorWarnings = generated.warnings.filter { it.startsWith("Monitor") }
        assertEquals(
            listOf(
                "Monitor 'data_variable' belongs to sprite 'Ghost', which is not in the project; it is not shown",
                "Monitor of variable 'nothing' matches no variable in the project; it is not shown",
                "Monitor for 'music_getTempo' is not supported yet; it is not shown",
            ),
            monitorWarnings,
            "only visible monitors warn, each with its own reason",
        )
    }

    @Test
    fun showingAVariableCreatedOnTheFlyHasAMonitor() {
        val generated = ProjectCompiler.compile(Sb3Parser(json).parseProject(json.parseProject(project)))
        val table = generated.resources.getValue(Monitors.RESOURCE).toString(Charsets.UTF_8).lines()
        // The stage variable's dropped record leaves it the default hidden monitor; the Cat's undeclared
        // variable gets one at the end of the table, as its first slot.
        assertTrue(table.any { it.startsWith("data_variable\tdefault\t\t0\t\ta\t") }, "stage variable: $table")
        assertTrue(table.any { it.startsWith("data_variable\tdefault\tCat\t0\t\tCat: foo\t") }, "Cat's foo: $table")
        val cat = org.jscratch.compiler.JScratch.disassemble(generated.classes.getValue("jscratch/project/Sprite_Cat"))
        assertTrue(cat.contains("setMonitorVisible"), "the show block calls the runtime")
        assertTrue(cat.contains("monitorSlot(I)"), "the Cat can report foo to its monitor")
    }
}
