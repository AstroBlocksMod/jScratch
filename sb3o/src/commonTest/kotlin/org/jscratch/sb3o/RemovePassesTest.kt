package org.jscratch.sb3o

import org.jscratch.sb3o.model.ProjectReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemovePassesTest {
    @Test
    fun removesStacksThatNeverRun() {
        val builder = ProjectBuilder()
        builder.script("hat", "s1" to "motion_movesteps", "s2" to "looks_say")
        builder.block(
            "loose",
            "looks_say",
            next = "loose2",
            topLevel = true,
            inputs = "\"MESSAGE\": [3, \"r\", [10, \"\"]]",
            comment = "c1",
        )
        builder.block("r", "operator_join", parent = "loose")
        builder.block("loose2", "control_if", parent = "loose", inputs = "\"SUBSTACK\": [2, \"inner\"]")
        builder.block("inner", "motion_turnleft", parent = "loose2")
        builder.block("reporter", "operator_add", topLevel = true)
        builder.block("unknownHat", "foo_whenSomethingHappens", next = "u1", topLevel = true)
        builder.block("u1", "motion_movesteps", parent = "unknownHat")
        builder.block("extension", "foo_doSomething", topLevel = true)
        val comment =
            """"c1": {"blockId": "loose", "x": 0, "y": 0, "width": 1, "height": 1, "minimized": false, "text": "t"}"""
        val project = ProjectReader.read(builder.build(comment))
        // A variable reporter on the workspace is a bare array, not a block object.
        val sprite = project.sprites.single()
        sprite.looseReporters["v1"] = org.jscratch.sb3o.json.JsonParser.parse("[12, \"x\", \"v\", 0, 0]")
        val report = SB3O.optimize(project, OptimizeOptions(foldConstants = false, removeUnusedProcedures = false))

        assertEquals(setOf("hat", "s1", "s2", "unknownHat", "u1", "extension"), sprite.blocks.keys)
        assertTrue(sprite.looseReporters.isEmpty())
        assertTrue(sprite.comments.isEmpty(), "the comment on the removed block should go with it")
        assertEquals(
            listOf(
                "S: removed a loose stack of 4 blocks starting with looks_say",
                "S: removed a loose stack of 1 block starting with operator_add",
                "S: removed 1 loose variable reporter",
            ),
            report.messages,
        )
    }

    @Test
    fun removesProceduresNoScriptReaches() {
        val builder = ProjectBuilder()
        builder.block("hat", "event_whenflagclicked", next = "call1", topLevel = true)
        builder.call("call1", "used %s", parent = "hat")
        builder.definition("used", "used %s", next = "callChained")
        builder.call("callChained", "chained", parent = "used")
        builder.definition("chained", "chained")
        builder.definition("unused", "unused", next = "m1")
        builder.block("m1", "motion_movesteps", parent = "unused")
        builder.definition("self", "self", next = "callSelf")
        builder.call("callSelf", "self", parent = "self")
        builder.definition("fromLoose", "from loose")
        builder.block("looseTop", "looks_hide", next = "loose", topLevel = true)
        builder.call("loose", "from loose", parent = "looseTop")
        val project = ProjectReader.read(builder.build())
        val report = SB3O.optimize(project, OptimizeOptions(foldConstants = false))
        val sprite = project.sprites.single()
        assertEquals(
            setOf("hat", "call1", "used", "used_proto", "callChained", "chained", "chained_proto"),
            sprite.blocks.keys,
        )
        assertEquals(
            listOf(
                "S: removed a loose stack of 2 blocks starting with looks_hide",
                "S: removed the unused custom block \"unused\" (3 blocks)",
                "S: removed the unused custom block \"self\" (3 blocks)",
                "S: removed the unused custom block \"from loose\" (2 blocks)",
            ),
            report.messages,
        )
    }

    @Test
    fun optimizesJsonText() {
        val builder = ProjectBuilder()
        builder.block("hat", "event_whenflagclicked", next = "say", topLevel = true)
        builder.block("say", "looks_say", parent = "hat", inputs = "\"MESSAGE\": ${covered("r", 10, "")}")
        builder.block(
            "r",
            "operator_add",
            parent = "say",
            inputs = "\"NUM1\": ${lit(4, "2")}, \"NUM2\": ${lit(4, "2")}",
        )
        val result = SB3O.optimizeJson(builder.build())
        assertTrue(result.projectJson.contains("\"MESSAGE\":[1,[10,4]]"), result.projectJson)
        assertEquals("3 blocks before, 2 after (1 change)", result.report.summary())
    }
}
