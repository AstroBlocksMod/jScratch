package org.jscratch.compiler.sb3

import org.jscratch.compiler.json.JsonArray
import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.ProjectJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class Sb3Exception(message: String) : RuntimeException(message)

/** An unpacked .sb3: the parsed project plus every other zip entry (costumes, sounds). */
class Sb3File(val project: Project, val assets: Map<String, ByteArray>)

/**
 * Reads .sb3 archives into the [Project] model.
 *
 * The archive is unzipped here with `java.util.zip`, which works both on the JVM
 * and under TeaVM. `project.json` is then handed to scratch-parser, the Scratch
 * Foundation's JavaScript parser, through [ProjectJson]; that parses and validates
 * it against the official sb3 schema. The resulting tree is mapped onto the model,
 * whose shape follows ScratchEverywhere's `sb3.cpp`: a flat list of targets, each
 * with variables, lists, broadcasts, costumes, sounds and a block table keyed by
 * block id. The block graph is kept as-is; the code generator walks `next` and
 * `SUBSTACK` links itself.
 */
class Sb3Parser(private val json: ProjectJson) {
    fun readSb3(bytes: ByteArray): Sb3File {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                // Some tools nest everything in a folder; keep only the file name.
                entries[entry.name.substringAfterLast('/')] = out.toByteArray()
            }
        }
        val projectJson = entries.remove("project.json") ?: throw Sb3Exception("project.json not found in archive")
        val project = parseProject(json.parseProject(projectJson.toString(Charsets.UTF_8)))
        return Sb3File(project, entries)
    }

    fun parseProject(root: JsonValue): Project {
        val targetsJson = root["targets"]?.asArray ?: throw Sb3Exception("project.json has no 'targets' array")
        val targets = targetsJson.items.map { parseTarget(it) }
        if (targets.none { it.isStage }) throw Sb3Exception("project has no stage")
        val monitors =
            root["monitors"]?.asArray?.items?.map { m ->
                val params = LinkedHashMap<String, String>()
                m["params"]?.asObject?.fields?.forEach { (k, v) -> params[k] = v.toText() }
                Monitor(
                    id = m["id"]?.asString ?: "",
                    opcode = m["opcode"]?.asString ?: "",
                    mode = m["mode"]?.asString ?: "default",
                    params = params,
                    spriteName = m["spriteName"]?.asString,
                    x = m["x"]?.asDouble ?: Double.NaN,
                    y = m["y"]?.asDouble ?: Double.NaN,
                    width = m["width"]?.asDouble ?: 0.0,
                    height = m["height"]?.asDouble ?: 0.0,
                    visible = m["visible"]?.asBoolean ?: false,
                    sliderMin = m["sliderMin"]?.asDouble ?: 0.0,
                    sliderMax = m["sliderMax"]?.asDouble ?: 100.0,
                    isDiscrete = m["isDiscrete"]?.asBoolean ?: true,
                )
            } ?: emptyList()
        val extensions = root["extensions"]?.asArray?.items?.mapNotNull { it.asString } ?: emptyList()
        return Project(targets, monitors, extensions)
    }

    private fun parseTarget(t: JsonValue): Target {
        val isStage = t["isStage"]?.asBoolean ?: false
        val name = t["name"]?.asString ?: if (isStage) "Stage" else "Sprite"

        val variables = LinkedHashMap<String, Variable>()
        t["variables"]?.asObject?.fields?.forEach { (id, data) ->
            val arr = data.asArray ?: return@forEach
            variables[id] =
                Variable(
                    id = id,
                    name = arr[0]?.toText() ?: id,
                    value = arr[1]?.toScratchValue() ?: "",
                    isCloud = arr.size >= 3,
                )
        }

        val lists = LinkedHashMap<String, ScratchListDef>()
        t["lists"]?.asObject?.fields?.forEach { (id, data) ->
            val arr = data.asArray ?: return@forEach
            val items = arr[1]?.asArray?.items?.map { it.toScratchValue() } ?: emptyList()
            lists[id] = ScratchListDef(id, arr[0]?.toText() ?: id, items)
        }

        val broadcasts = LinkedHashMap<String, String>()
        t["broadcasts"]?.asObject?.fields?.forEach { (id, data) -> broadcasts[id] = data.toText() }

        val costumes =
            t["costumes"]?.asArray?.items?.map { c ->
                val format = c["dataFormat"]?.asString ?: "png"
                val assetId = c["assetId"]?.asString ?: ""
                Costume(
                    name = c["name"]?.toText() ?: "costume",
                    assetId = assetId,
                    md5ext = c["md5ext"]?.asString ?: "$assetId.$format",
                    dataFormat = format,
                    rotationCenterX = c["rotationCenterX"]?.asDouble ?: 0.0,
                    rotationCenterY = c["rotationCenterY"]?.asDouble ?: 0.0,
                    bitmapResolution = c["bitmapResolution"]?.asDouble ?: 1.0,
                )
            } ?: emptyList()

        val sounds =
            t["sounds"]?.asArray?.items?.map { s ->
                val format = s["dataFormat"]?.asString ?: "wav"
                val assetId = s["assetId"]?.asString ?: ""
                Sound(
                    name = s["name"]?.toText() ?: "sound",
                    assetId = assetId,
                    md5ext = s["md5ext"]?.asString ?: "$assetId.$format",
                    dataFormat = format,
                )
            } ?: emptyList()

        val blocks = LinkedHashMap<String, Block>()
        t["blocks"]?.asObject?.fields?.forEach { (id, data) ->
            // Top-level variable reporters dragged onto the workspace are stored as bare arrays. Skip them.
            if (data.asObject == null) return@forEach
            blocks[id] = parseBlock(id, data)
        }

        return Target(
            name = name,
            isStage = isStage,
            variables = variables,
            lists = lists,
            broadcasts = broadcasts,
            blocks = blocks,
            costumes = costumes,
            sounds = sounds,
            currentCostume = t["currentCostume"]?.asDouble?.toInt() ?: 0,
            volume = t["volume"]?.asDouble ?: 100.0,
            layerOrder = t["layerOrder"]?.asDouble?.toInt() ?: 0,
            visible = t["visible"]?.asBoolean ?: true,
            x = t["x"]?.asDouble ?: 0.0,
            y = t["y"]?.asDouble ?: 0.0,
            size = t["size"]?.asDouble ?: 100.0,
            direction = t["direction"]?.asDouble ?: 90.0,
            draggable = t["draggable"]?.asBoolean ?: false,
            rotationStyle = t["rotationStyle"]?.asString ?: "all around",
        )
    }

    private fun parseBlock(id: String, b: JsonValue): Block {
        val inputs = LinkedHashMap<String, Input>()
        b["inputs"]?.asObject?.fields?.forEach { (name, data) -> inputs[name] = parseInput(data) }
        val fields = LinkedHashMap<String, Field>()
        b["fields"]?.asObject?.fields?.forEach { (name, data) ->
            val arr = data.asArray
            if (arr != null) {
                fields[name] = Field(arr[0]?.toText() ?: "", arr[1]?.takeIf { !it.isNull }?.toText())
            } else {
                fields[name] = Field(data.toText(), null)
            }
        }
        return Block(
            id = id,
            opcode = b["opcode"]?.asString ?: "",
            next = b["next"]?.asString,
            parent = b["parent"]?.asString,
            inputs = inputs,
            fields = fields,
            shadow = b["shadow"]?.asBoolean ?: false,
            topLevel = b["topLevel"]?.asBoolean ?: false,
            mutation = b["mutation"]?.let { parseMutation(it) },
        )
    }

    private fun parseMutation(m: JsonValue): Mutation? {
        val proccode = m["proccode"]?.asString ?: return null
        val warpRaw = m["warp"]
        val warp = warpRaw?.asBoolean ?: (warpRaw?.asString == "true")
        return Mutation(
            proccode = proccode,
            argumentIds = stringList(m["argumentids"]),
            argumentNames = stringList(m["argumentnames"]),
            argumentDefaults = stringList(m["argumentdefaults"]),
            warp = warp,
        )
    }

    /** Mutation lists are JSON arrays serialized into strings. */
    private fun stringList(v: JsonValue?): List<String> {
        val text = v?.asString ?: return emptyList()
        return try {
            (json.parse(text) as? JsonArray)?.items?.map { it.toText() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseInput(data: JsonValue): Input {
        val arr = data.asArray ?: return Input.Empty
        // [shadowType, value, obscuredShadow?]; the value is a block id, a primitive array or null.
        val value = arr[1] ?: return Input.Empty
        return when {
            value.asString != null -> Input.BlockRef(value.asString!!)
            value.asArray != null -> parsePrimitive(value.asArray!!)
            else -> Input.Empty
        }
    }

    private fun parsePrimitive(p: JsonArray): Input {
        val type = p[0]?.asDouble?.toInt() ?: return Input.Empty
        return when (type) {
            PrimitiveType.BROADCAST -> Input.BroadcastRef(p[1]?.toText() ?: "", p[2]?.toText() ?: "")
            PrimitiveType.VARIABLE -> Input.VariableRef(p[1]?.toText() ?: "", p[2]?.toText() ?: "")
            PrimitiveType.LIST -> Input.ListRef(p[1]?.toText() ?: "", p[2]?.toText() ?: "")
            else -> Input.Literal(type, p[1]?.toText() ?: "")
        }
    }
}
