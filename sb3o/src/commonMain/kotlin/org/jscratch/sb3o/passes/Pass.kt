package org.jscratch.sb3o.passes

import org.jscratch.sb3o.model.Project

/** What the passes did to a project, as lines a tool can print. */
class OptimizeReport {
    val messages = ArrayList<String>()
    var blocksBefore = 0
    var blocksAfter = 0

    fun add(message: String) {
        messages.add(message)
    }

    /** One line: the block counts and how many messages there are. */
    fun summary(): String =
        "$blocksBefore blocks before, $blocksAfter after (${messages.size} change${if (messages.size == 1) "" else "s"})"
}

/** One rewrite of a project. A pass must leave the project behaving exactly as it did. */
interface Pass {
    val name: String

    fun run(project: Project, report: OptimizeReport)
}
