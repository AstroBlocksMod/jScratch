package org.jscratch.sb3o

import org.jscratch.sb3o.model.Project
import org.jscratch.sb3o.model.ProjectReader
import org.jscratch.sb3o.model.ProjectWriter
import org.jscratch.sb3o.passes.FoldConstants
import org.jscratch.sb3o.passes.OptimizeReport
import org.jscratch.sb3o.passes.Pass
import org.jscratch.sb3o.passes.RemoveLooseStacks
import org.jscratch.sb3o.passes.RemoveUnusedProcedures

/** Which passes to run. Every pass is on by default. */
class OptimizeOptions(
    val removeLooseStacks: Boolean = true,
    val foldConstants: Boolean = true,
    val removeUnusedProcedures: Boolean = true,
)

/** The outcome of [SB3O.optimizeJson]: the optimized `project.json` and what changed. */
class OptimizedJson(val projectJson: String, val report: OptimizeReport)

/**
 * SB3O, the Scratch 3 project optimizer. It rewrites a project at the block level so that it
 * does the same thing with fewer blocks, the way a minifier does for a program: constant
 * expressions are computed once, control blocks whose condition is known are decided, and
 * stacks that can never run are dropped. The output opens in the Scratch editor and runs the
 * same on scratch-vm, TurboWarp and jScratch.
 */
object SB3O {
    /** The passes in the order they run, for the given options. */
    fun passes(options: OptimizeOptions = OptimizeOptions()): List<Pass> {
        val passes = ArrayList<Pass>()
        if (options.removeLooseStacks) passes.add(RemoveLooseStacks)
        if (options.foldConstants) passes.add(FoldConstants)
        if (options.removeUnusedProcedures) passes.add(RemoveUnusedProcedures)
        return passes
    }

    /** Optimizes [project] in place. */
    fun optimize(project: Project, options: OptimizeOptions = OptimizeOptions()): OptimizeReport {
        val report = OptimizeReport()
        report.blocksBefore = countBlocks(project)
        for (pass in passes(options)) pass.run(project, report)
        report.blocksAfter = countBlocks(project)
        return report
    }

    /** Optimizes the text of a `project.json`. The assets of the project are unaffected. */
    fun optimizeJson(projectJson: String, options: OptimizeOptions = OptimizeOptions()): OptimizedJson {
        val project = ProjectReader.read(projectJson)
        val report = optimize(project, options)
        return OptimizedJson(ProjectWriter.writeText(project), report)
    }

    fun countBlocks(project: Project): Int = project.targets.sumOf { it.blocks.size + it.looseReporters.size }
}
