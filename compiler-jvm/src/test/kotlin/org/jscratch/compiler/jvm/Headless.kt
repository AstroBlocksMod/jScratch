package org.jscratch.compiler.jvm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.fail

private val java = File(System.getProperty("jscratch.test.javaHome"), "bin/java")

/** What the runtime logs when it cannot use a costume or sound from the JAR. */
private val ASSET_FAILURES =
    Regex(
        """\[jscratch] (Could not load costume|Could not decode sound|Could not play sound|Sound '.*' \(.*\) is neither)""",
    )

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
        if (ASSET_FAILURES.containsMatchIn(stderr)) fail("compiled project could not use an asset. stderr:\n$stderr")
        if (stderr.isNotBlank()) println("child stderr:\n$stderr")
        return stdout
    } finally {
        tmp.delete()
    }
}

/**
 * A project for one sprite called [sprite] with the given [blocks] (the JSON members of its `blocks`
 * object) and [variables] (name to initial value), packed with the costumes of hello.sb3 from
 * [samplesDir]: the stage wears Blue Sky and the sprite wears the cat. For tests that need blocks no
 * sample has, or more of them than anyone would write by hand.
 */
internal fun syntheticSb3(
    samplesDir: File,
    sprite: String,
    blocks: String,
    variables: Map<String, Any> = emptyMap(),
): ByteArray {
    val vars = variables.entries.joinToString(", ") { (name, value) ->
        val json = if (value is String) "\"$value\"" else value.toString()
        "\"v_$name\": [\"$name\", $json]"
    }
    val project =
        """
        {
          "targets": [
            {
              "isStage": true, "name": "Stage", "variables": {}, "lists": {}, "broadcasts": {},
              "blocks": {}, "comments": {}, "currentCostume": 0,
              "costumes": [{"name": "Blue Sky", "bitmapResolution": 1, "dataFormat": "svg",
                            "assetId": "e7c147730f19d284bcd7b3f00af19bb6",
                            "rotationCenterX": 240, "rotationCenterY": 180}],
              "sounds": [], "volume": 100, "layerOrder": 0, "tempo": 60,
              "videoTransparency": 50, "videoState": "on", "textToSpeechLanguage": null
            },
            {
              "isStage": false, "name": "$sprite", "variables": { $vars },
              "lists": {}, "broadcasts": {}, "blocks": { $blocks },
              "comments": {}, "currentCostume": 0,
              "costumes": [{"name": "Cat", "bitmapResolution": 1, "dataFormat": "svg",
                            "assetId": "6ac3a6339b85092fa9b1694f2da7779d",
                            "rotationCenterX": 48, "rotationCenterY": 50}],
              "sounds": [], "volume": 100, "layerOrder": 1, "visible": true,
              "x": 0, "y": 0, "size": 100, "direction": 90, "draggable": false,
              "rotationStyle": "all around"
            }
          ],
          "monitors": [], "extensions": [],
          "meta": {"semver": "3.0.0", "vm": "2.0.0", "agent": "jscratch-test"}
        }
        """.trimIndent()
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        ZipInputStream(ByteArrayInputStream(File(samplesDir, "hello.sb3").readBytes())).use { src ->
            while (true) {
                val entry = src.nextEntry ?: break
                if (entry.name == "project.json") continue
                zip.putNextEntry(ZipEntry(entry.name))
                src.copyTo(zip)
            }
        }
        zip.putNextEntry(ZipEntry("project.json"))
        zip.write(project.toByteArray(Charsets.UTF_8))
    }
    return out.toByteArray()
}
