package org.jscratch.sb3o.passes

import org.jscratch.sb3o.model.Project

/**
 * Removes stacks that nothing can run: a top-level block that is not a hat only runs when it is
 * clicked in the editor. The same goes for reporters dropped onto the workspace by themselves,
 * variable reporters included. Blocks of unknown categories (other extensions) are left alone,
 * since one of them may be a hat the pass does not know.
 */
object RemoveLooseStacks : Pass {
    override val name = "remove-loose-stacks"

    override fun run(project: Project, report: OptimizeReport) {
        for (target in project.targets) {
            for (top in target.topLevelBlocks) {
                if (top.shadow || top.id !in target.blocks) continue
                if (Blocks.isHat(top.opcode) || !Blocks.isKnownCategory(top.opcode)) continue
                val stack = Blocks.stack(target, top)
                Blocks.remove(target, stack)
                val size = "${stack.size} block${plural(stack.size)}"
                report.add("${target.name}: removed a loose stack of $size starting with ${top.opcode}")
            }
            if (target.looseReporters.isNotEmpty()) {
                val n = target.looseReporters.size
                target.looseReporters.clear()
                report.add("${target.name}: removed $n loose variable reporter${plural(n)}")
            }
        }
    }
}

internal fun plural(n: Int): String = if (n == 1) "" else "s"
