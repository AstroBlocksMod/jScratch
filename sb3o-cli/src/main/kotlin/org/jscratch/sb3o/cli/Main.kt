package org.jscratch.sb3o.cli

import org.jscratch.sb3o.Archives
import org.jscratch.sb3o.OptimizeOptions
import org.jscratch.sb3o.SB3O
import org.jscratch.sb3o.json.JsonException
import org.jscratch.sb3o.model.Sb3Exception
import org.jscratch.sb3o.passes.OptimizeReport
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
SB3O: optimizes Scratch 3 projects at the block level.

Usage: sb3o <project.sb3 | project.json> [options]

The input is an .sb3 file, or a bare project.json. The output is the same kind
of file, with the same costumes and sounds, that does the same thing with fewer
blocks: constant expressions are computed, control blocks with a known condition
are decided, and stacks that can never run and custom blocks nothing calls are
removed. It opens in the Scratch editor like any other project.

Options:
  -o, --output <file>       Where to write the result (default: <name>-optimized.sb3
                            or <name>-optimized.json next to the input)
  --no-fold                 Keep constant expressions and control blocks as they are
  --no-remove-loose         Keep stacks that cannot run
  --no-remove-procedures    Keep custom blocks that nothing calls
  -q, --quiet               Report only errors
  -h, --help                Show this help
"""

fun main(args: Array<String>) {
    var input: String? = null
    var output: File? = null
    var fold = true
    var removeLoose = true
    var removeProcedures = true
    var quiet = false

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-h", "--help" -> {
                println(USAGE.trim())
                return
            }
            "-o", "--output" -> {
                i++
                if (i >= args.size) fail("missing value for $a")
                output = File(args[i])
            }
            "--no-fold" -> fold = false
            "--no-remove-loose" -> removeLoose = false
            "--no-remove-procedures" -> removeProcedures = false
            "-q", "--quiet" -> quiet = true
            else -> {
                if (a.startsWith("-")) fail("unknown option $a")
                if (input != null) fail("only one input is supported")
                input = a
            }
        }
        i++
    }

    val inFile = File(input ?: fail("no input given\n\n${USAGE.trim()}"))
    if (!inFile.isFile) fail("${inFile.path} does not exist")
    val data = inFile.readBytes()
    val options = OptimizeOptions(removeLoose, fold, removeProcedures)
    val base = File(inFile.parentFile ?: File("."), inFile.nameWithoutExtension)

    val result: ByteArray
    val outFile: File
    try {
        if (Archives.isZip(data)) {
            val entries = Archives.read(data)
            val projectJson = entries.remove("project.json") ?: fail("${inFile.path} has no project.json")
            val optimized = SB3O.optimizeJson(projectJson.toString(Charsets.UTF_8), options)
            report(optimized.report, quiet)
            result = Archives.writeProject(optimized.projectJson.toByteArray(Charsets.UTF_8), entries)
            outFile = output ?: File("${base.path}-optimized.sb3")
        } else {
            val optimized = SB3O.optimizeJson(data.toString(Charsets.UTF_8), options)
            report(optimized.report, quiet)
            result = optimized.projectJson.toByteArray(Charsets.UTF_8)
            outFile = output ?: File("${base.path}-optimized.json")
        }
    } catch (e: Sb3Exception) {
        fail("${inFile.path}: ${e.message}")
    } catch (e: JsonException) {
        fail("${inFile.path}: not a Scratch 3 project: ${e.message}")
    }
    outFile.absoluteFile.parentFile?.mkdirs()
    outFile.writeBytes(result)
    if (!quiet) System.err.println("wrote ${outFile.path} (${result.size / 1024} KiB)")
}

private fun report(report: OptimizeReport, quiet: Boolean) {
    if (quiet) return
    for (m in report.messages) System.err.println(m)
    System.err.println(report.summary())
}

private fun fail(message: String): Nothing {
    System.err.println("sb3o: $message")
    exitProcess(2)
}
