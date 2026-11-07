package org.jscratch.sb3o.passes

import org.jscratch.sb3o.model.Block
import org.jscratch.sb3o.model.Project
import org.jscratch.sb3o.model.Target

/**
 * Removes custom block definitions that no script of their sprite can reach. A call names its
 * procedure by `proccode` in the sprite it sits in, so a definition is reachable when a stack
 * under a hat calls it, directly or through other reachable definitions. A definition only
 * called by itself, or by a definition nothing calls, goes too. Runs after [RemoveLooseStacks],
 * so calls in loose stacks do not keep a definition alive.
 */
object RemoveUnusedProcedures : Pass {
    override val name = "remove-unused-procedures"

    override fun run(project: Project, report: OptimizeReport) {
        for (target in project.targets) {
            val definitions = LinkedHashMap<String, Block>()
            val roots = ArrayList<Block>()
            for (top in target.topLevelBlocks) {
                val proccode = if (top.opcode == "procedures_definition") proccodeOf(target, top) else null
                if (proccode != null) {
                    definitions[proccode] = top
                } else if (!top.shadow) {
                    roots.add(top)
                }
            }
            if (definitions.isEmpty()) continue

            val reached = HashSet<String>()
            val work = ArrayDeque(roots)
            while (work.isNotEmpty()) {
                val top = work.removeFirst()
                for (b in Blocks.stack(target, top)) {
                    if (b.opcode != "procedures_call") continue
                    val proccode = b.mutation?.proccode ?: continue
                    if (reached.add(proccode)) definitions[proccode]?.let { work.add(it) }
                }
            }

            for ((proccode, definition) in definitions) {
                if (proccode in reached) continue
                val stack = Blocks.stack(target, definition)
                Blocks.remove(target, stack)
                val size = "${stack.size} block${plural(stack.size)}"
                report.add("${target.name}: removed the unused custom block \"$proccode\" ($size)")
            }
        }
    }

    /** The proccode of a `procedures_definition`, found on its prototype. */
    fun proccodeOf(target: Target, definition: Block): String? =
        Blocks.substack(target, definition, "custom_block")?.mutation?.proccode
}
