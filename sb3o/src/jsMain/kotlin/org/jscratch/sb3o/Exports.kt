@file:OptIn(ExperimentalJsExport::class)

package org.jscratch.sb3o

// The JavaScript API of the sb3o package. Kotlin/JS exports these with TypeScript declarations.
// The Kotlin API itself is not exported, since its collections and sealed classes do not map onto
// JavaScript well.

/** Which passes to run. Every pass is on by default. */
@JsExport
class JsOptimizeOptions(
    val removeLooseStacks: Boolean = true,
    val foldConstants: Boolean = true,
    val removeUnusedProcedures: Boolean = true,
)

/** The optimized `project.json` and what changed. */
@JsExport
class JsOptimizeResult(
    val projectJson: String,
    val messages: Array<String>,
    val blocksBefore: Int,
    val blocksAfter: Int,
    val summary: String,
)

/** Optimizes the text of a `project.json` and returns the new text with a report. */
@JsExport
fun optimizeProjectJson(projectJson: String, options: JsOptimizeOptions = JsOptimizeOptions()): JsOptimizeResult {
    val result =
        SB3O.optimizeJson(
            projectJson,
            OptimizeOptions(options.removeLooseStacks, options.foldConstants, options.removeUnusedProcedures),
        )
    val report = result.report
    return JsOptimizeResult(
        result.projectJson,
        report.messages.toTypedArray(),
        report.blocksBefore,
        report.blocksAfter,
        report.summary(),
    )
}
