package org.jscratch.sb3o.passes

import org.jscratch.sb3o.model.Block
import org.jscratch.sb3o.model.Input
import org.jscratch.sb3o.model.PrimitiveType
import org.jscratch.sb3o.model.Project
import org.jscratch.sb3o.model.Target
import org.jscratch.sb3o.values.JsMath
import org.jscratch.sb3o.values.JsNumbers
import org.jscratch.sb3o.values.ScratchValues

/**
 * Computes blocks whose inputs are all literals and puts the result in their place, and
 * simplifies control blocks whose condition is known.
 *
 * Only the operator reporters are computed, with scratch-vm's own arithmetic and casting rules
 * ([ScratchValues]), and a result only replaces a reporter when the slot can hold it without any
 * block seeing a difference:
 *
 * - The slot has to have a literal shadow (the text or number box the reporter covers). A boolean
 *   slot or a menu has none, so nothing is written there.
 * - A number goes into a number or text shadow as a JSON number, the encoding Scratch 2 imports
 *   use, so the block reports a number as the reporter did. `say` rounds numbers and not text,
 *   and `pick random` treats them differently, so writing text would change those. NaN, the
 *   infinities and negative zero have no JSON form that reads back the same, so they stay blocks.
 *   Number shadows with limits (positive, whole, integer, angle) only take what the editor's
 *   fields would show unchanged.
 * - Text goes into a text shadow as it is. A number shadow would cast it on load.
 * - A boolean result never goes into a value slot; it only decides `if`, `if else`, `repeat until`
 *   and `wait until`, which take no shadow, and folds further through `not`, `and` and `or`.
 *
 * `if` and `if else` run their chosen branch in place without yielding, so the branch is spliced
 * into the script. `repeat until` and `wait until` with a true condition, and `repeat` with a count
 * that rounds to zero or less, end at once without yielding and are removed.
 */
object FoldConstants : Pass {
    override val name = "fold-constants"

    override fun run(project: Project, report: OptimizeReport) {
        for (target in project.targets) Folder(target, report).run()
    }

    private val NOT_CONSTANT = Any()

    /** The `mathop` functions every platform computes exactly. */
    private val EXACT_MATHOPS = setOf("abs", "floor", "ceiling", "sqrt")

    private class Folder(val target: Target, val report: OptimizeReport) {
        private val memo = HashMap<String, Any>()
        var folded = 0
        var simplified = 0

        fun run() {
            // A block may be removed while an earlier one is handled, so walk a snapshot and check.
            for (id in target.blocks.keys.toList()) {
                val block = target.blocks[id] ?: continue
                if (block.shadow) continue
                foldInputs(block)
                simplifyControl(block)
            }
            if (folded > 0) report.add("${target.name}: folded $folded constant expression${plural(folded)}")
            if (simplified > 0) {
                report.add(
                    "${target.name}: decided $simplified control block${plural(simplified)} with a constant condition",
                )
            }
        }

        private fun foldInputs(block: Block) {
            for ((name, input) in block.inputs.entries.toList()) {
                if (input !is Input.BlockRef) continue
                val shadow = input.shadow as? Input.Literal ?: continue
                val reporter = target.blocks[input.id] ?: continue
                val value = evaluate(reporter)
                if (value === NOT_CONSTANT) continue
                val literal = literalFor(value, shadow) ?: continue
                block.inputs[name] = literal
                Blocks.removeTree(target, reporter)
                folded++
            }
        }

        /** The literal to put in a slot for a folded [value], or null when the slot cannot hold it faithfully. */
        private fun literalFor(value: Any, shadow: Input.Literal): Input.Literal? = when (value) {
            is Double -> {
                val ok =
                    value.isFinite() && !(value == 0.0 && 1.0 / value < 0) &&
                        when (shadow.type) {
                            PrimitiveType.NUMBER, PrimitiveType.TEXT -> true
                            PrimitiveType.POSITIVE_NUMBER -> value >= 0
                            PrimitiveType.WHOLE_NUMBER -> value >= 0 && isInteger(value)
                            PrimitiveType.INTEGER -> isInteger(value)
                            PrimitiveType.ANGLE -> value > -180 && value <= 180
                            else -> false
                        }
                if (ok) Input.Literal(shadow.type, JsNumbers.toString(value), value) else null
            }
            is String -> if (shadow.type == PrimitiveType.TEXT) Input.Literal(PrimitiveType.TEXT, value) else null
            else -> null
        }

        private fun isInteger(value: Double): Boolean = value == kotlin.math.floor(value)

        private fun simplifyControl(block: Block) {
            when (block.opcode) {
                "control_if" -> {
                    val condition = conditionOf(block) ?: return
                    replaceWithBranch(block, if (condition) "SUBSTACK" else null)
                }
                "control_if_else" -> {
                    val condition = conditionOf(block) ?: return
                    replaceWithBranch(block, if (condition) "SUBSTACK" else "SUBSTACK2")
                }
                "control_repeat_until", "control_wait_until" -> {
                    if (conditionOf(block) == true) replaceWithBranch(block, null)
                }
                "control_repeat" -> {
                    val times = constantOf(block.inputs["TIMES"])
                    if (times !== NOT_CONSTANT && ScratchValues.jsRound(ScratchValues.toNumber(times)) <= 0) {
                        replaceWithBranch(block, null)
                    }
                }
            }
        }

        /** The condition of a control block when it is constant, as a boolean; null when it is not. */
        private fun conditionOf(block: Block): Boolean? {
            val input = block.inputs["CONDITION"]
            // An empty condition is undefined in scratch-vm, which casts to false.
            if (input == null || input is Input.Empty) return false
            val value = constantOf(input)
            return if (value === NOT_CONSTANT) null else ScratchValues.toBoolean(value)
        }

        /** Takes [block] out of its script, keeping only the chain in its [branch] input, if any. */
        private fun replaceWithBranch(block: Block, branch: String?) {
            val kept = branch?.let { Blocks.substack(target, block, it) }
            val removed = Blocks.tree(target, block).filter { it.id != block.id }
            val keptIds = kept?.let { Blocks.stack(target, it).map { b -> b.id }.toSet() } ?: emptySet()
            Blocks.splice(target, block, kept)
            Blocks.remove(target, removed.filter { it.id !in keptIds } + block)
            simplified++
        }

        /** The constant value of an input, or [NOT_CONSTANT]. */
        private fun constantOf(input: Input?): Any = when (input) {
            is Input.Literal -> input.numberValue ?: input.value
            is Input.BlockRef -> target.blocks[input.id]?.let { evaluate(it) } ?: NOT_CONSTANT
            else -> NOT_CONSTANT
        }

        /** What [block] reports, when that follows from its inputs alone, or [NOT_CONSTANT]. */
        private fun evaluate(block: Block): Any {
            memo[block.id]?.let { return it }
            val value = compute(block)
            memo[block.id] = value
            return value
        }

        private fun compute(block: Block): Any {
            fun arg(name: String): Any = constantOf(block.inputs[name])
            fun num(name: String): Double? =
                arg(name).let { if (it === NOT_CONSTANT) null else ScratchValues.toNumber(it) }
            fun str(name: String): String? =
                arg(name).let { if (it === NOT_CONSTANT) null else ScratchValues.toString(it) }
            fun bool(name: String): Boolean? =
                arg(name).let { if (it === NOT_CONSTANT) null else ScratchValues.toBoolean(it) }
            fun cmp(): Double? {
                val a = arg("OPERAND1")
                val b = arg("OPERAND2")
                return if (a === NOT_CONSTANT || b === NOT_CONSTANT) null else ScratchValues.compare(a, b)
            }
            val result: Any? =
                when (block.opcode) {
                    "operator_add" -> num("NUM1")?.let { a -> num("NUM2")?.let { b -> a + b } }
                    "operator_subtract" -> num("NUM1")?.let { a -> num("NUM2")?.let { b -> a - b } }
                    "operator_multiply" -> num("NUM1")?.let { a -> num("NUM2")?.let { b -> a * b } }
                    "operator_divide" -> num("NUM1")?.let { a -> num("NUM2")?.let { b -> a / b } }
                    "operator_mod" -> num("NUM1")?.let { a -> num("NUM2")?.let { b -> ScratchValues.mod(a, b) } }
                    "operator_round" -> num("NUM")?.let { ScratchValues.jsRound(it) }
                    "operator_mathop" -> {
                        // The transcendental functions only fold where the platform's are scratch-vm's own.
                        val operator = block.fields["OPERATOR"]?.value?.lowercase()
                        if (operator == null || !(JsMath.exact || operator in EXACT_MATHOPS)) {
                            null
                        } else {
                            num("NUM")?.let { ScratchValues.mathop(operator, it) }
                        }
                    }
                    "operator_join" -> str("STRING1")?.let { a -> str("STRING2")?.let { b -> a + b } }
                    "operator_length" -> str("STRING")?.length?.toDouble()
                    "operator_letter_of" -> {
                        val index = num("LETTER")?.let { it - 1 }
                        val s = str("STRING")
                        if (index == null || s == null) {
                            null
                        } else if (index < 0 || index >= s.length) {
                            ""
                        } else {
                            s[index.toInt()].toString()
                        }
                    }
                    "operator_contains" ->
                        str("STRING1")?.let { a -> str("STRING2")?.let { b -> a.lowercase().contains(b.lowercase()) } }
                    "operator_lt" -> cmp()?.let { it < 0 }
                    "operator_equals" -> cmp()?.let { it == 0.0 }
                    "operator_gt" -> cmp()?.let { it > 0 }
                    "operator_and" -> bool("OPERAND1")?.let { a -> bool("OPERAND2")?.let { b -> a && b } }
                    "operator_or" -> bool("OPERAND1")?.let { a -> bool("OPERAND2")?.let { b -> a || b } }
                    "operator_not" -> bool("OPERAND")?.let { !it }
                    else -> null
                }
            return result ?: NOT_CONSTANT
        }
    }
}
