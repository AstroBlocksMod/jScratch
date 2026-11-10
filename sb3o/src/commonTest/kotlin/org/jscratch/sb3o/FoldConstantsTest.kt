package org.jscratch.sb3o

import org.jscratch.sb3o.model.Input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldConstantsTest {
    private val foldOnly = OptimizeOptions(removeLooseStacks = false, removeUnusedProcedures = false)

    /** `say <reporter>` under a flag hat, with the reporter's blocks added by [reporter]. */
    private fun sayProject(reporter: ProjectBuilder.() -> Unit, shadowType: Int = 10): ProjectBuilder {
        val b = ProjectBuilder()
        b.block("hat", "event_whenflagclicked", next = "say", topLevel = true)
        b.block("say", "looks_say", parent = "hat", inputs = "\"MESSAGE\": ${covered("r", shadowType, "Hello!")}")
        b.reporter()
        return b
    }

    private fun ProjectBuilder.binary(
        id: String,
        opcode: String,
        parent: String,
        a: String,
        b: String,
        names: Pair<String, String> = "NUM1" to "NUM2",
    ) {
        block(id, opcode, parent = parent, inputs = "\"${names.first}\": $a, \"${names.second}\": $b")
    }

    @Test
    fun foldsArithmeticIntoANumber() {
        val result = optimize(sayProject({ binary("r", "operator_add", "say", lit(4, "2"), lit(4, "2")) }), foldOnly)
        assertEquals("[1,[10,4]]", result.input("say", "MESSAGE"))
        assertNull(result.sprite.blocks["r"], "the folded reporter should be gone")
        assertEquals(listOf("S: folded 1 constant expression"), result.report.messages)
        assertEquals(3, result.report.blocksBefore)
        assertEquals(2, result.report.blocksAfter)
    }

    @Test
    fun foldsNestedReportersAndText() {
        val project =
            sayProject({
                binary("r", "operator_join", "say", lit(10, "a"), "[3, \"inner\", [10, \"\"]]", "STRING1" to "STRING2")
                binary("inner", "operator_join", "r", "[3, \"len\", [10, \"\"]]", lit(10, "c"), "STRING1" to "STRING2")
                block("len", "operator_length", parent = "inner", inputs = "\"STRING\": ${lit(10, "héllo😀")}")
            })
        val result = optimize(project, foldOnly)
        assertEquals("[1,[10,\"a7c\"]]", result.input("say", "MESSAGE"))
        assertEquals(setOf("hat", "say"), result.sprite.blocks.keys)
    }

    @Test
    fun keepsNumbersAsNumbersInTextSlots() {
        val result = optimize(sayProject({ binary("r", "operator_divide", "say", lit(4, "1"), lit(4, "3")) }), foldOnly)
        assertEquals("[1,[10,0.3333333333333333]]", result.input("say", "MESSAGE"))
        val literal = assertIs<Input.Literal>(result.sprite.blocks.getValue("say").inputs.getValue("MESSAGE"))
        assertEquals(1.0 / 3, literal.numberValue)
    }

    @Test
    fun leavesValuesNoLiteralCanHold() {
        for ((a, b, op) in listOf(
            Triple("1", "0", "operator_divide"),
            Triple("0", "0", "operator_divide"),
            Triple("0", "-1", "operator_multiply"),
        )) {
            val result = optimize(sayProject({ binary("r", op, "say", lit(4, a), lit(4, b)) }), foldOnly)
            assertTrue("r" in result.sprite.blocks, "$a $op $b should not fold")
        }
        // A boolean never goes into a value slot.
        val bool =
            optimize(
                sayProject({
                    binary("r", "operator_equals", "say", lit(10, "1"), lit(10, "1"), "OPERAND1" to "OPERAND2")
                }),
                foldOnly,
            )
        assertTrue("r" in bool.sprite.blocks)
        // Text does not go into a number slot.
        val text =
            optimize(
                sayProject({
                    binary("r", "operator_join", "say", lit(10, "1"), lit(10, "2"), "STRING1" to "STRING2")
                }, shadowType = 4),
                foldOnly,
            )
        assertTrue("r" in text.sprite.blocks)
    }

    @Test
    fun respectsTheLimitsOfNumberShadows() {
        fun with(opcode: String, input: String, shadowType: Int, a: String, b: String): Optimized {
            val builder = ProjectBuilder()
            builder.block("hat", "event_whenflagclicked", next = "c", topLevel = true)
            builder.block("c", opcode, parent = "hat", inputs = "\"$input\": ${covered("r", shadowType, "10")}")
            builder.binary("r", "operator_divide", "c", lit(4, a), lit(4, b))
            return optimize(builder, foldOnly)
        }
        assertEquals("[1,[6,2]]", with("control_repeat", "TIMES", 6, "4", "2").input("c", "TIMES"))
        assertTrue("r" in with("control_repeat", "TIMES", 6, "5", "2").sprite.blocks, "2.5 is not a whole number")
        assertEquals("[1,[5,2.5]]", with("control_wait", "DURATION", 5, "5", "2").input("c", "DURATION"))
        assertTrue("r" in with("control_wait", "DURATION", 5, "-4", "2").sprite.blocks, "-2 is not positive")
        assertEquals(
            "[1,[8,-90]]",
            with("motion_pointindirection", "DIRECTION", 8, "-180", "2").input("c", "DIRECTION"),
        )
        assertTrue("r" in with("motion_pointindirection", "DIRECTION", 8, "-360", "2").sprite.blocks, "-180 wraps")
    }

    @Test
    fun leavesReportersWithVariablesAndMenus() {
        val withVariable =
            optimize(
                sayProject({
                    binary("r", "operator_add", "say", "[3, [12, \"x\", \"v\"], [4, \"0\"]]", lit(4, "1"))
                }),
                foldOnly,
            )
        assertTrue("r" in withVariable.sprite.blocks)

        val builder = ProjectBuilder()
        builder.block("hat", "event_whenflagclicked", next = "sw", topLevel = true)
        builder.block("sw", "looks_switchcostumeto", parent = "hat", inputs = "\"COSTUME\": [3, \"r\", \"menu\"]")
        builder.block(
            "menu",
            "looks_costume",
            parent = "sw",
            shadow = true,
            fields = "\"COSTUME\": [\"costume1\", null]",
        )
        builder.binary("r", "operator_add", "sw", lit(4, "1"), lit(4, "1"))
        val withMenu = optimize(builder, foldOnly)
        assertTrue("r" in withMenu.sprite.blocks, "a menu slot has no literal to write")
    }

    @Test
    fun computesTheOperatorsLikeScratch() {
        fun fold(opcode: String, a: String, b: String, names: Pair<String, String> = "NUM1" to "NUM2"): String? {
            val result = optimize(sayProject({ binary("r", opcode, "say", lit(10, a), lit(10, b), names) }), foldOnly)
            return result.input("say", "MESSAGE")
        }
        assertEquals("[1,[10,2]]", fold("operator_mod", "-7", "3"))
        assertEquals("[1,[10,0.30000000000000004]]", fold("operator_add", "0.1", "0.2"))
        assertEquals("[1,[10,255]]", fold("operator_add", "0xff", ""))
        assertEquals("[1,[10,1]]", fold("operator_add", "abc", "1"))
        assertEquals("[1,[10,\"a\"]]", fold("operator_letter_of", "1.5", "abc", "LETTER" to "STRING"))
        assertEquals("[1,[10,\"\"]]", fold("operator_letter_of", "0", "abc", "LETTER" to "STRING"))
        assertEquals("[1,[10,\"😀\"]]", fold("operator_join", "", "😀", "STRING1" to "STRING2"))

        val round =
            optimize(
                sayProject({
                    block("r", "operator_round", parent = "say", inputs = "\"NUM\": ${lit(4, "2.5")}")
                }),
                foldOnly,
            )
        assertEquals("[1,[10,3]]", round.input("say", "MESSAGE"))
        fun mathop(operator: String, n: String): Optimized = optimize(
            sayProject({
                block(
                    "r",
                    "operator_mathop",
                    parent = "say",
                    inputs = "\"NUM\": ${lit(4, n)}",
                    fields = "\"OPERATOR\": [\"$operator\", null]",
                )
            }),
            foldOnly,
        )
        assertEquals("[1,[10,4]]", mathop("sqrt", "16").input("say", "MESSAGE"))
        assertEquals("[1,[10,-3]]", mathop("floor", "-2.5").input("say", "MESSAGE"))
        // sin, cos and tan fold when rounding to 10 decimals hides the engines' last-bit differences.
        assertEquals("[1,[10,0.5]]", mathop("sin", "30").input("say", "MESSAGE"))
        assertEquals("[1,[10,0.5]]", mathop("cos", "60").input("say", "MESSAGE"))
        assertEquals("[1,[10,1]]", mathop("tan", "405").input("say", "MESSAGE"))
        // The unrounded functions differ between engines in the last bits, and so does reducing huge angles.
        val unfolded =
            listOf("e ^" to "1", "ln" to "2", "log" to "1000", "10 ^" to "0.3", "asin" to "0.5", "sin" to "1e6")
        for ((operator, n) in unfolded) {
            assertTrue("r" in mathop(operator, n).sprite.blocks, "$operator $n should stay a block")
        }
    }

    @Test
    fun decidesIfBlocks() {
        val builder = ProjectBuilder()
        builder.block("hat", "event_whenflagclicked", next = "if", topLevel = true)
        builder.block(
            "if",
            "control_if_else",
            next = "after",
            parent = "hat",
            inputs = "\"CONDITION\": [2, \"eq\"], \"SUBSTACK\": [2, \"a1\"], \"SUBSTACK2\": [2, \"b1\"]",
        )
        builder.binary("eq", "operator_equals", "if", lit(10, "1"), lit(10, "1.0"), "OPERAND1" to "OPERAND2")
        builder.block("a1", "motion_movesteps", next = "a2", parent = "if", inputs = "\"STEPS\": ${lit(4, "10")}")
        builder.block("a2", "motion_turnright", parent = "a1", inputs = "\"DEGREES\": ${lit(4, "15")}", comment = "c1")
        builder.block("b1", "motion_turnleft", parent = "if", inputs = "\"DEGREES\": ${lit(4, "15")}", comment = "c2")
        builder.block("after", "looks_say", parent = "if", inputs = "\"MESSAGE\": ${lit(10, "done")}")
        val comments = """"c1": {"blockId": "a2", "x": 0, "y": 0, "width": 1, "height": 1, "minimized": false, "text": "kept"},
            "c2": {"blockId": "b1", "x": 0, "y": 0, "width": 1, "height": 1, "minimized": false, "text": "dropped"}"""
        val project = org.jscratch.sb3o.model.ProjectReader.read(builder.build(comments))
        val report = SB3O.optimize(project, foldOnly)
        val sprite = project.sprites.single()
        assertEquals(setOf("hat", "a1", "a2", "after"), sprite.blocks.keys)
        assertEquals("a1", sprite.blocks.getValue("hat").next)
        assertEquals("hat", sprite.blocks.getValue("a1").parent)
        assertEquals("after", sprite.blocks.getValue("a2").next)
        assertEquals("a2", sprite.blocks.getValue("after").parent)
        assertFalse(sprite.blocks.getValue("a1").topLevel)
        assertEquals(setOf("c1"), sprite.comments.keys)
        assertEquals(listOf("S: decided 1 control block with a constant condition"), report.messages)
    }

    @Test
    fun removesIfBlocksThatNeverRunAndEmptyConditions() {
        val builder = ProjectBuilder()
        builder.block("hat", "event_whenflagclicked", next = "if", topLevel = true)
        builder.block(
            "if",
            "control_if",
            next = "if2",
            parent = "hat",
            inputs = "\"CONDITION\": [2, \"not\"], \"SUBSTACK\": [2, \"a1\"]",
        )
        builder.block("not", "operator_not", parent = "if", inputs = "\"OPERAND\": [2, \"lt\"]")
        builder.binary("lt", "operator_lt", "not", lit(10, "1"), lit(10, "2"), "OPERAND1" to "OPERAND2")
        builder.block("a1", "motion_movesteps", parent = "if", inputs = "\"STEPS\": ${lit(4, "10")}")
        // An empty condition is false, and so is a substack that is empty either way.
        builder.block("if2", "control_if", next = "after", parent = "if", inputs = "\"SUBSTACK\": [2, \"b1\"]")
        builder.block("b1", "motion_turnleft", parent = "if2", inputs = "\"DEGREES\": ${lit(4, "15")}")
        builder.block("after", "looks_say", parent = "if2", inputs = "\"MESSAGE\": ${lit(10, "done")}")
        val result = optimize(builder, foldOnly)
        assertEquals(setOf("hat", "after"), result.sprite.blocks.keys)
        assertEquals("after", result.sprite.blocks.getValue("hat").next)
        assertEquals("hat", result.sprite.blocks.getValue("after").parent)
    }

    @Test
    fun removesLoopsThatEndAtOnce() {
        val builder = ProjectBuilder()
        builder.block("hat", "event_whenflagclicked", next = "until", topLevel = true)
        builder.block(
            "until",
            "control_repeat_until",
            next = "rep",
            parent = "hat",
            inputs = "\"CONDITION\": [2, \"eq\"], \"SUBSTACK\": [2, \"a1\"]",
        )
        builder.binary("eq", "operator_equals", "until", lit(10, "a"), lit(10, "A"), "OPERAND1" to "OPERAND2")
        builder.block("a1", "motion_movesteps", parent = "until", inputs = "\"STEPS\": ${lit(4, "10")}")
        builder.block(
            "rep",
            "control_repeat",
            next = "wait",
            parent = "until",
            inputs = "\"TIMES\": ${lit(6, "0.4")}, \"SUBSTACK\": [2, \"b1\"]",
        )
        builder.block("b1", "motion_movesteps", parent = "rep", inputs = "\"STEPS\": ${lit(4, "10")}")
        builder.block(
            "wait",
            "control_wait_until",
            next = "keep",
            parent = "rep",
            inputs = "\"CONDITION\": [2, \"gt\"]",
        )
        builder.binary("gt", "operator_gt", "wait", lit(10, "2"), lit(10, "1"), "OPERAND1" to "OPERAND2")
        // A loop that never ends has to stay, as does one with a condition nobody can decide.
        builder.block(
            "keep",
            "control_repeat_until",
            next = "keep2",
            parent = "wait",
            inputs = "\"CONDITION\": [2, \"lt\"], \"SUBSTACK\": [2, \"c1\"]",
        )
        builder.binary("lt", "operator_lt", "keep", lit(10, "2"), lit(10, "1"), "OPERAND1" to "OPERAND2")
        builder.block("c1", "motion_movesteps", parent = "keep", inputs = "\"STEPS\": ${lit(4, "10")}")
        builder.block(
            "keep2",
            "control_repeat",
            parent = "keep",
            inputs = "\"TIMES\": ${lit(6, "1")}, \"SUBSTACK\": [2, \"d1\"]",
        )
        builder.block("d1", "motion_movesteps", parent = "keep2", inputs = "\"STEPS\": ${lit(4, "10")}")
        val result = optimize(builder, foldOnly)
        assertEquals(setOf("hat", "keep", "lt", "c1", "keep2", "d1"), result.sprite.blocks.keys)
        assertEquals("keep", result.sprite.blocks.getValue("hat").next)
        assertEquals("hat", result.sprite.blocks.getValue("keep").parent)
    }

    @Test
    fun aTopLevelIfLeavesItsBranchOnTheWorkspace() {
        val builder = ProjectBuilder()
        builder.block(
            "if",
            "control_if",
            parent = null,
            topLevel = true,
            inputs = "\"CONDITION\": [2, \"eq\"], \"SUBSTACK\": [2, \"a1\"]",
        )
        builder.binary("eq", "operator_equals", "if", lit(10, "1"), lit(10, "1"), "OPERAND1" to "OPERAND2")
        builder.block("a1", "motion_movesteps", parent = "if", inputs = "\"STEPS\": ${lit(4, "10")}")
        val result = optimize(builder, foldOnly)
        val a1 = result.sprite.blocks.getValue("a1")
        assertEquals(setOf("a1"), result.sprite.blocks.keys)
        assertTrue(a1.topLevel)
        assertNull(a1.parent)
        assertEquals("10", a1.extra["x"].toString())
    }
}
