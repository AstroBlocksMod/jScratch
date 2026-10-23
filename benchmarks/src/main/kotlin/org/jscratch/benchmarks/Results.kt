package org.jscratch.benchmarks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** One project on one engine: wall time of every run from the green flag until all scripts finished. */
class BenchmarkResult(
    val name: String,
    val times: List<Double>,
    val frames: Int,
    val output: String?,
    val error: String?,
) {
    val median: Double?
        get() {
            if (times.isEmpty()) return null
            val sorted = times.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
}

/**
 * The results file of one engine. js/run.js writes the same format for scratch-vm
 * and TurboWarp, so keep the two in step.
 */
class EngineResults(
    val engine: String,
    val name: String,
    val version: String,
    val platform: String,
    val date: String,
    val benchmarks: List<BenchmarkResult>,
) {
    fun write(file: File) {
        val json =
            buildJsonObject {
                put("engine", engine)
                put("name", name)
                put("version", version)
                put("platform", platform)
                put("date", date)
                put(
                    "benchmarks",
                    JsonArray(
                        benchmarks.map { b ->
                            buildJsonObject {
                                put("name", b.name)
                                put("times", JsonArray(b.times.map { JsonPrimitive(it) }))
                                put("frames", b.frames)
                                put("output", b.output)
                                put("error", b.error)
                            }
                        },
                    ),
                )
            }
        file.parentFile.mkdirs()
        file.writeText(prettyJson.encodeToString(JsonObject.serializer(), json) + "\n")
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }

        fun read(file: File): EngineResults {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            return EngineResults(
                engine = root.text("engine") ?: file.nameWithoutExtension,
                name = root.text("name") ?: file.nameWithoutExtension,
                version = root.text("version") ?: "",
                platform = root.text("platform") ?: "",
                date = root.text("date") ?: "",
                benchmarks =
                root.getValue("benchmarks").jsonArray.map { e ->
                    val o = e.jsonObject
                    BenchmarkResult(
                        name = o.text("name") ?: "",
                        times = o["times"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty(),
                        frames = o["frames"]?.jsonPrimitive?.int ?: 0,
                        output = o.text("output"),
                        error = o.text("error"),
                    )
                },
            )
        }
    }
}
