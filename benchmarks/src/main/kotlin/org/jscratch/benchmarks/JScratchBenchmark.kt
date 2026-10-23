package org.jscratch.benchmarks

import org.jscratch.compiler.JScratch
import org.jscratch.compiler.jvm.RhinoProjectJson
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val MAX_FRAMES = 100_000
private const val TIMEOUT_SECONDS = 300L
private val timingLine = Regex("""^jscratch: (\d+) frames in ([\d.]+) ms$""", RegexOption.MULTILINE)
private val sayLine = Regex("""^.+? (?:says|thinks): (.*)$""")

/**
 * Compiles every benchmark project and times it headless on jScratch.
 *
 *   JScratchBenchmarkKt <sb3 dir> <runtime jar> <results.json> <runs> [name,name,...]
 *
 * Each run is a fresh JVM, so the time includes JIT warm-up the way a player would
 * see it, but not JVM startup or asset loading: the runtime measures from the green
 * flag until the last script finishes (`-Djscratch.timing`).
 */
fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: <sb3 dir> <runtime jar> <results.json> <runs> [names]" }
    val sb3Dir = File(args[0])
    val runtimeJar = File(args[1]).readBytes()
    val resultsFile = File(args[2])
    val runs = maxOf(1, args[3].toInt())
    val only = args.getOrNull(4).orEmpty().split(",").filter { it.isNotEmpty() }
    val jarDir = File(resultsFile.parentFile, "jars").apply { mkdirs() }
    val java = File(System.getProperty("java.home"), "bin/java").path
    val json = RhinoProjectJson()

    val projects =
        sb3Dir
            .listFiles { f -> f.name.endsWith(".sb3") }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .filter { only.isEmpty() || it in only }
            .sorted()

    val benchmarks =
        projects.map { name ->
            val result = JScratch.compile(File(sb3Dir, "$name.sb3").readBytes(), runtimeJar, json)
            val jar = File(jarDir, "$name.jar").apply { writeBytes(result.jar) }
            val times = ArrayList<Double>()
            var frames = 0
            var output: String? = null
            var error: String? = null
            for (i in 0 until runs) {
                val run = runOnce(java, jar)
                if (run.error != null) {
                    error = run.error
                    break
                }
                times.add(run.ms)
                frames = run.frames
                output = run.output
            }
            val shown =
                error?.let { "failed: $it" }
                    ?: (times.joinToString(", ") { String.format(Locale.ROOT, "%.1f", it) } + " ms")
            println("jScratch $name: $shown")
            BenchmarkResult(name, times, frames, output, error)
        }

    val version = System.getProperty("jscratch.benchmark.version") ?: "dev"
    val platform = "JDK ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})"
    EngineResults("jscratch", "jScratch", "jScratch $version", platform, Instant.now().toString(), benchmarks)
        .write(resultsFile)
}

private class Run(val ms: Double, val frames: Int, val output: String?, val error: String?)

private fun runOnce(java: String, jar: File): Run {
    val process =
        ProcessBuilder(
            java,
            "-Djscratch.headless=$MAX_FRAMES",
            "-Djscratch.timing=true",
            "-Djava.awt.headless=true",
            "-jar",
            jar.path,
        ).start()
    val stdout = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return Run(0.0, 0, null, "did not finish within $TIMEOUT_SECONDS s")
    }
    val err = stderr.get()
    if (process.exitValue() != 0) return Run(0.0, 0, null, "exited with ${process.exitValue()}: ${err.trim()}")
    val timing = timingLine.find(err) ?: return Run(0.0, 0, null, "no timing line in stderr: ${err.trim()}")
    val frames = timing.groupValues[1].toInt()
    if (frames >= MAX_FRAMES) return Run(0.0, 0, null, "did not finish within $MAX_FRAMES frames")
    val output = stdout.get().lines().mapNotNull { sayLine.find(it)?.groupValues?.get(1) }.lastOrNull()
    return Run(timing.groupValues[2].toDouble(), frames, output, null)
}
