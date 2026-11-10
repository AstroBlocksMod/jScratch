package org.jscratch.sb3o.model

import org.jscratch.sb3o.json.JsonArray
import org.jscratch.sb3o.json.JsonObject
import org.jscratch.sb3o.json.JsonParser
import org.jscratch.sb3o.json.JsonValue

class Sb3Exception(message: String) : RuntimeException(message)

/**
 * Maps a `project.json` tree onto the [Project] model. The tree comes from [JsonParser] or from
 * the host's own parser; nothing is validated against the sb3 schema here, so a host that wants
 * that (jScratch runs scratch-parser first) does it before. The block graph is kept as-is: the
 * passes and the code generator walk `next` and `SUBSTACK` links themselves.
 */
object ProjectReader {
    fun read(text: String): Project = read(JsonParser.parse(text))

    fun read(root: JsonValue): Project {
        val targetsJson = root["targets"]?.asArray ?: throw Sb3Exception("project.json has no 'targets' array")
        val targets = targetsJson.items.mapTo(ArrayList()) { parseTarget(it) }
        if (targets.none { it.isStage }) throw Sb3Exception("project has no stage")
        val monitors = root["monitors"]?.asArray?.items?.mapTo(ArrayList()) { parseMonitor(it) } ?: ArrayList()
        val extensions = root["extensions"]?.asArray?.items?.mapNotNullTo(ArrayList()) { it.asString } ?: ArrayList()
        return Project(targets, monitors, extensions, extraFields(root, PROJECT_KEYS))
    }

    private fun parseMonitor(m: JsonValue): Monitor {
        val params = LinkedHashMap<String, String>()
        m["params"]?.asObject?.fields?.forEach { (k, v) -> params[k] = v.toText() }
        return Monitor(
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
            extra = extraFields(m, MONITOR_KEYS),
        )
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
                    extra = extraFields(c, COSTUME_KEYS),
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
                    extra = extraFields(s, SOUND_KEYS),
                )
            } ?: emptyList()

        val blocks = LinkedHashMap<String, Block>()
        val looseReporters = LinkedHashMap<String, JsonValue>()
        t["blocks"]?.asObject?.fields?.forEach { (id, data) ->
            // Top-level variable reporters dragged onto the workspace are stored as bare arrays.
            if (data.asObject == null) looseReporters[id] = data else blocks[id] = parseBlock(id, data)
        }

        val comments = LinkedHashMap<String, JsonValue>()
        t["comments"]?.asObject?.fields?.forEach { (id, data) -> comments[id] = data }

        return Target(
            name = name,
            isStage = isStage,
            variables = variables,
            lists = lists,
            broadcasts = broadcasts,
            blocks = blocks,
            looseReporters = looseReporters,
            comments = comments,
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
            extra = extraFields(t, TARGET_KEYS),
        )
    }

    private fun parseBlock(id: String, b: JsonValue): Block {
        val inputs = LinkedHashMap<String, Input>()
        b["inputs"]?.asObject?.fields?.forEach { (name, data) -> inputs[name] = parseInput(data) }
        val fields = LinkedHashMap<String, Field>()
        b["fields"]?.asObject?.fields?.forEach { (name, data) ->
            val arr = data.asArray
            if (arr != null) {
                fields[name] = Field(arr[0]?.toText() ?: "", arr[1]?.takeIf { !it.isNull }?.toText(), data)
            } else {
                fields[name] = Field(data.toText(), null, data)
            }
        }
        val mutationJson = b["mutation"]
        return Block(
            id = id,
            opcode = b["opcode"]?.asString ?: "",
            next = b["next"]?.asString,
            parent = b["parent"]?.asString,
            inputs = inputs,
            fields = fields,
            shadow = b["shadow"]?.asBoolean ?: false,
            topLevel = b["topLevel"]?.asBoolean ?: false,
            mutation = mutationJson?.let { parseMutation(it) },
            mutationJson = mutationJson,
            extra = extraFields(b, BLOCK_KEYS),
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
            (JsonParser.parse(text) as? JsonArray)?.items?.map { it.toText() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseInput(data: JsonValue): Input {
        val arr = data.asArray ?: return Input.Empty(data)
        // [shadowType, value, obscuredShadow?]; the value is a block id, a primitive array or null.
        val value = arr[1] ?: return Input.Empty(data)
        val shadow = arr[2]?.let { parseInputValue(it, null) }
        return parseInputValue(value, shadow, data) ?: Input.Empty(data)
    }

    /** A block id or a primitive array, as found in an input's value or shadow position. */
    private fun parseInputValue(value: JsonValue, shadow: Input?, raw: JsonValue? = null): Input? {
        value.asString?.let { return Input.BlockRef(it, shadow, raw) }
        val p = value.asArray ?: return null
        val type = p[0]?.asDouble?.toInt() ?: return null
        return when (type) {
            PrimitiveType.BROADCAST -> Input.BroadcastRef(p[1]?.toText() ?: "", p[2]?.toText() ?: "", shadow, raw)
            PrimitiveType.VARIABLE -> Input.VariableRef(p[1]?.toText() ?: "", p[2]?.toText() ?: "", shadow, raw)
            PrimitiveType.LIST -> Input.ListRef(p[1]?.toText() ?: "", p[2]?.toText() ?: "", shadow, raw)
            else -> Input.Literal(type, p[1]?.toText() ?: "", p[1]?.asDouble, raw)
        }
    }

    private fun extraFields(obj: JsonValue, known: Set<String>): LinkedHashMap<String, JsonValue> {
        val extra = LinkedHashMap<String, JsonValue>()
        (obj as? JsonObject)?.fields?.forEach { (k, v) -> if (k !in known) extra[k] = v }
        return extra
    }

    internal val PROJECT_KEYS = setOf("targets", "monitors", "extensions")
    internal val TARGET_KEYS =
        setOf(
            "isStage", "name", "variables", "lists", "broadcasts", "blocks", "comments", "currentCostume",
            "costumes", "sounds", "volume", "layerOrder", "visible", "x", "y", "size", "direction", "draggable",
            "rotationStyle",
        )
    internal val BLOCK_KEYS = setOf("opcode", "next", "parent", "inputs", "fields", "shadow", "topLevel", "mutation")
    internal val COSTUME_KEYS =
        setOf("name", "assetId", "md5ext", "dataFormat", "rotationCenterX", "rotationCenterY", "bitmapResolution")
    internal val SOUND_KEYS = setOf("name", "assetId", "md5ext", "dataFormat")
    internal val MONITOR_KEYS =
        setOf(
            "id", "opcode", "mode", "params", "spriteName", "x", "y", "width", "height", "visible", "sliderMin",
            "sliderMax", "isDiscrete",
        )
}
