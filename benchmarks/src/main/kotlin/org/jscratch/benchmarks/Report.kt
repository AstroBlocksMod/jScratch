package org.jscratch.benchmarks

import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.system.exitProcess

/** Engines in the order the report shows them. Anything else found in the results directory goes last. */
private val engineOrder = listOf("jscratch", "turbowarp", "scratch-vm")

/**
 * Merges the latest results of every engine into one Markdown report and prints it.
 *
 *   ReportKt <projects dir> <results dir> <report.md>
 *
 * Exits with an error when jScratch failed a project or said something other than
 * the expected output, so a CI run catches wrong results, not only slow ones.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: <projects dir> <results dir> <report.md>" }
    val projectsDir = File(args[0])
    val resultsDir = File(args[1])
    val reportFile = File(args[2])

    val engines =
        resultsDir
            .listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .map { EngineResults.read(it) }
            .sortedBy { engineOrder.indexOf(it.engine).let { i -> if (i < 0) engineOrder.size else i } }
    if (engines.isEmpty()) {
        println("No benchmark results in $resultsDir yet.")
        return
    }

    val projects = engines.flatMap { e -> e.benchmarks.map { it.name } }.distinct().sorted()
    val meta = projects.associateWith { readProperties(File(projectsDir, "$it/benchmark.properties")) }
    val jscratch = engines.firstOrNull { it.engine == "jscratch" }
    val others = engines.filter { it !== jscratch }
    val problems = ArrayList<String>()
    var jscratchFailed = false

    val out = StringBuilder()
    out.appendLine("# jScratch benchmarks")
    out.appendLine()
    out.appendLine(
        "Median wall time in milliseconds from the green flag until every script has finished. " +
            "Lower is better. Every run starts a fresh process, so JIT warm-up counts, but startup " +
            "and project loading do not. Nothing is drawn.",
    )
    out.appendLine()

    val header = ArrayList<String>()
    header.add("Benchmark")
    engines.forEach { header.add(it.name) }
    if (jscratch != null) others.forEach { header.add("jScratch vs ${it.name}") }
    out.appendLine(header.joinToString(" | ", "| ", " |"))
    out.appendLine(
        header.indices.joinToString(" | ", "| ", " |") { i -> if (i == 0 || i > engines.size) "---" else "---:" },
    )

    for (name in projects) {
        val expected = meta[name]?.getProperty("expected")
        val row = ArrayList<String>()
        row.add(name)
        for (e in engines) {
            val b = e.benchmarks.firstOrNull { it.name == name }
            row.add(cell(b, expected))
            val problem =
                when {
                    b == null -> null
                    b.error != null -> "${e.name} failed `$name`: ${b.error.lineSequence().first()}"
                    expected != null && b.output != expected ->
                        "${e.name} said `${b.output}` for `$name`, expected `$expected`."
                    else -> null
                }
            if (problem != null) {
                problems.add(problem)
                if (e === jscratch) jscratchFailed = true
            }
        }
        if (jscratch != null) {
            val mine = jscratch.benchmarks.firstOrNull { it.name == name }?.median
            for (e in others) row.add(ratio(mine, e.benchmarks.firstOrNull { it.name == name }?.median))
        }
        out.appendLine(row.joinToString(" | ", "| ", " |"))
    }

    out.appendLine()
    out.appendLine("## Engines")
    out.appendLine()
    for (e in engines) {
        val runs = e.benchmarks.maxOfOrNull { it.times.size } ?: 0
        val runText = if (runs == 1) "1 run" else "$runs runs"
        out.appendLine("- **${e.name}**: ${e.version} on ${e.platform}, $runText per project, measured ${e.date}.")
    }

    out.appendLine()
    out.appendLine("## Projects")
    out.appendLine()
    for (name in projects) {
        val p = meta[name]
        val description = p?.getProperty("description") ?: "No description."
        val expected = p?.getProperty("expected")?.let { " Expected output: `$it`." } ?: ""
        out.appendLine("- **$name**: $description$expected")
    }

    if (problems.isNotEmpty()) {
        out.appendLine()
        out.appendLine("## Problems")
        out.appendLine()
        problems.forEach { out.appendLine("- $it") }
    }

    reportFile.parentFile.mkdirs()
    reportFile.writeText(out.toString())
    println(out)
    println("Report written to $reportFile")

    if (jscratchFailed) {
        System.err.println("jScratch failed or gave a wrong result on at least one benchmark.")
        exitProcess(1)
    }
}

private fun readProperties(file: File): Properties {
    val p = Properties()
    if (file.isFile) file.reader().use { p.load(it) }
    return p
}

private fun cell(b: BenchmarkResult?, expected: String?): String {
    if (b == null) return "n/a"
    if (b.error != null) return "failed"
    val ms = b.median?.let { format(it) } ?: "n/a"
    return if (expected != null && b.output != expected) "$ms (wrong)" else ms
}

/** How jScratch compares with another engine, from the two medians. */
private fun ratio(mine: Double?, theirs: Double?): String {
    if (mine == null || theirs == null || mine <= 0.0 || theirs <= 0.0) return "n/a"
    return if (mine > theirs) "${format(mine / theirs)}x slower" else "${format(theirs / mine)}x faster"
}

private fun format(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
