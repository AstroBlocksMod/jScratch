package org.jscratch.sb3o.model

import org.jscratch.sb3o.json.JsonArray
import org.jscratch.sb3o.json.JsonBoolean
import org.jscratch.sb3o.json.JsonNull
import org.jscratch.sb3o.json.JsonNumber
import org.jscratch.sb3o.json.JsonObject
import org.jscratch.sb3o.json.JsonString
import org.jscratch.sb3o.json.JsonValue
import org.jscratch.sb3o.json.JsonWriter
import org.jscratch.sb3o.json.scratchValueToJson

/**
 * Writes the [Project] model back as `project.json`, in the layout scratch-vm's serializer
 * produces. Inputs and fields that still carry the JSON they were read from are written back as
 * they were; the ones a pass made are encoded from the model.
 */
object ProjectWriter {
    fun writeText(project: Project): String = JsonWriter.write(write(project))

    fun write(project: Project): JsonObject {
        val root = JsonObject()
        root["targets"] = JsonArray(project.targets.mapTo(ArrayList()) { writeTarget(it) })
        root["monitors"] = JsonArray(project.monitors.mapTo(ArrayList()) { writeMonitor(it) })
        root["extensions"] = JsonArray(project.extensions.mapTo(ArrayList<JsonValue>()) { JsonString(it) })
        root.fields.putAll(project.extra)
        return root
    }

    private fun writeTarget(t: Target): JsonObject {
        val obj = JsonObject()
        obj["isStage"] = JsonBoolean(t.isStage)
        obj["name"] = JsonString(t.name)
        val variables = JsonObject()
        for ((id, v) in t.variables) {
            val arr = JsonArray(JsonString(v.name), scratchValueToJson(v.value))
            if (v.isCloud) arr.items.add(JsonBoolean(true))
            variables[id] = arr
        }
        obj["variables"] = variables
        val lists = JsonObject()
        for ((id, l) in t.lists) {
            lists[id] = JsonArray(JsonString(l.name), JsonArray(l.items.mapTo(ArrayList()) { scratchValueToJson(it) }))
        }
        obj["lists"] = lists
        val broadcasts = JsonObject()
        for ((id, name) in t.broadcasts) broadcasts[id] = JsonString(name)
        obj["broadcasts"] = broadcasts
        val blocks = JsonObject()
        for ((id, b) in t.blocks) blocks[id] = writeBlock(b, t)
        blocks.fields.putAll(t.looseReporters)
        obj["blocks"] = blocks
        obj["comments"] = JsonObject(LinkedHashMap(t.comments))
        obj["currentCostume"] = JsonNumber(t.currentCostume.toDouble())
        obj["costumes"] = JsonArray(t.costumes.mapTo(ArrayList()) { writeCostume(it) })
        obj["sounds"] = JsonArray(t.sounds.mapTo(ArrayList()) { writeSound(it) })
        obj["volume"] = JsonNumber(t.volume)
        obj["layerOrder"] = JsonNumber(t.layerOrder.toDouble())
        if (!t.isStage) {
            obj["visible"] = JsonBoolean(t.visible)
            obj["x"] = JsonNumber(t.x)
            obj["y"] = JsonNumber(t.y)
            obj["size"] = JsonNumber(t.size)
            obj["direction"] = JsonNumber(t.direction)
            obj["draggable"] = JsonBoolean(t.draggable)
            obj["rotationStyle"] = JsonString(t.rotationStyle)
        }
        obj.fields.putAll(t.extra)
        return obj
    }

    private fun writeCostume(c: Costume): JsonObject {
        val obj = JsonObject()
        obj["name"] = JsonString(c.name)
        obj["bitmapResolution"] = JsonNumber(c.bitmapResolution)
        obj["dataFormat"] = JsonString(c.dataFormat)
        obj["assetId"] = JsonString(c.assetId)
        obj["md5ext"] = JsonString(c.md5ext)
        obj["rotationCenterX"] = JsonNumber(c.rotationCenterX)
        obj["rotationCenterY"] = JsonNumber(c.rotationCenterY)
        obj.fields.putAll(c.extra)
        return obj
    }

    private fun writeSound(s: Sound): JsonObject {
        val obj = JsonObject()
        obj["name"] = JsonString(s.name)
        obj["assetId"] = JsonString(s.assetId)
        obj["dataFormat"] = JsonString(s.dataFormat)
        obj["md5ext"] = JsonString(s.md5ext)
        obj.fields.putAll(s.extra)
        return obj
    }

    private fun writeMonitor(m: Monitor): JsonObject {
        val obj = JsonObject()
        obj["id"] = JsonString(m.id)
        obj["mode"] = JsonString(m.mode)
        obj["opcode"] = JsonString(m.opcode)
        val params = JsonObject()
        for ((k, v) in m.params) params[k] = JsonString(v)
        obj["params"] = params
        obj["spriteName"] = m.spriteName?.let { JsonString(it) } ?: JsonNull
        obj["width"] = JsonNumber(m.width)
        obj["height"] = JsonNumber(m.height)
        if (!m.x.isNaN()) obj["x"] = JsonNumber(m.x)
        if (!m.y.isNaN()) obj["y"] = JsonNumber(m.y)
        obj["visible"] = JsonBoolean(m.visible)
        obj["sliderMin"] = JsonNumber(m.sliderMin)
        obj["sliderMax"] = JsonNumber(m.sliderMax)
        obj["isDiscrete"] = JsonBoolean(m.isDiscrete)
        obj.fields.putAll(m.extra)
        return obj
    }

    private fun writeBlock(b: Block, target: Target): JsonObject {
        val obj = JsonObject()
        obj["opcode"] = JsonString(b.opcode)
        obj["next"] = b.next?.let { JsonString(it) } ?: JsonNull
        obj["parent"] = b.parent?.let { JsonString(it) } ?: JsonNull
        val inputs = JsonObject()
        for ((name, input) in b.inputs) inputs[name] = input.raw ?: encodeInput(input, target)
        obj["inputs"] = inputs
        val fields = JsonObject()
        for ((name, field) in b.fields) {
            fields[name] = field.raw ?: JsonArray(JsonString(field.value), field.id?.let { JsonString(it) } ?: JsonNull)
        }
        obj["fields"] = fields
        obj["shadow"] = JsonBoolean(b.shadow)
        obj["topLevel"] = JsonBoolean(b.topLevel)
        obj.fields.putAll(b.extra)
        b.mutationJson?.let { obj["mutation"] = it }
        return obj
    }

    /** Encodes an input a pass made: `[1, shadow]`, `[2, block]` or `[3, block, shadow]`. */
    fun encodeInput(input: Input, target: Target): JsonValue = when (input) {
        is Input.Empty -> JsonArray(JsonNumber(InputShadow.SAME_BLOCK.toDouble()), JsonNull)
        is Input.Literal -> JsonArray(JsonNumber(InputShadow.SAME_BLOCK.toDouble()), encodeValue(input))
        is Input.BlockRef -> {
            val shadow = input.shadow
            when {
                shadow != null ->
                    JsonArray(JsonNumber(InputShadow.OBSCURED.toDouble()), JsonString(input.id), encodeValue(shadow))
                target.blocks[input.id]?.shadow == true ->
                    JsonArray(JsonNumber(InputShadow.SAME_BLOCK.toDouble()), JsonString(input.id))
                else -> JsonArray(JsonNumber(InputShadow.NO_SHADOW.toDouble()), JsonString(input.id))
            }
        }
        else -> {
            val shadow = input.shadow
            if (shadow == null) {
                JsonArray(JsonNumber(InputShadow.SAME_BLOCK.toDouble()), encodeValue(input))
            } else {
                JsonArray(JsonNumber(InputShadow.OBSCURED.toDouble()), encodeValue(input), encodeValue(shadow))
            }
        }
    }

    /** A block id or a primitive array, as it goes in an input's value or shadow position. */
    private fun encodeValue(input: Input): JsonValue = when (input) {
        is Input.BlockRef -> JsonString(input.id)
        is Input.Literal -> {
            // Literal only holds finite numbers, so this never writes the null JSON gives NaN and infinity.
            val value = input.numberValue?.let { JsonNumber(it) } ?: JsonString(input.value)
            JsonArray(JsonNumber(input.type.toDouble()), value)
        }
        is Input.BroadcastRef ->
            JsonArray(JsonNumber(PrimitiveType.BROADCAST.toDouble()), JsonString(input.name), JsonString(input.id))
        is Input.VariableRef ->
            JsonArray(JsonNumber(PrimitiveType.VARIABLE.toDouble()), JsonString(input.name), JsonString(input.id))
        is Input.ListRef ->
            JsonArray(JsonNumber(PrimitiveType.LIST.toDouble()), JsonString(input.name), JsonString(input.id))
        is Input.Empty -> JsonNull
    }
}
