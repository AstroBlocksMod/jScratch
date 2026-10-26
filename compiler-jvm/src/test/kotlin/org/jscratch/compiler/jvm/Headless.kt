package org.jscratch.compiler.jvm

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.fail

private val java = File(System.getProperty("jscratch.test.javaHome"), "bin/java")

/**
 * Runs a compiled project headless in a child JVM for up to [frames] frames and returns what it
 * printed. Fails when the runtime could not load a costume or sound.
 */
internal fun runHeadless(jar: ByteArray, frames: Int, vararg properties: String): String {
    val tmp = Files.createTempFile("jscratch-test", ".jar").toFile()
    try {
        tmp.writeBytes(jar)
        val command =
            listOf(java.path, "-Djscratch.headless=$frames", "-Djava.awt.headless=true") + properties +
                listOf("-jar", tmp.path)
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("compiled project did not finish within 60 s. stderr:\n$stderr")
        }
        if (process.exitValue() != 0) fail("compiled project exited with ${process.exitValue()}. stderr:\n$stderr")
        if (stderr.contains(
                "[jscratch] Could not load",
            )
        ) {
            fail("compiled project could not load an asset. stderr:\n$stderr")
        }
        if (stderr.isNotBlank()) println("child stderr:\n$stderr")
        return stdout
    } finally {
        tmp.delete()
    }
}
