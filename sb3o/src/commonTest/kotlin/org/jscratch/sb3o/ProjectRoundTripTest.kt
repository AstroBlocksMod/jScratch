package org.jscratch.sb3o

import org.jscratch.sb3o.json.JsonArray
import org.jscratch.sb3o.json.JsonObject
import org.jscratch.sb3o.json.JsonParser
import org.jscratch.sb3o.json.JsonValue
import org.jscratch.sb3o.model.Input
import org.jscratch.sb3o.model.ProjectReader
import org.jscratch.sb3o.model.ProjectWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A small project with the encodings the writer has to put back as it found them. */
internal const val SAMPLE_PROJECT = """
{
  "targets": [
    {
      "isStage": true, "name": "Stage",
      "variables": {"v1": ["score", 3], "v2": ["cloud", "0", true]},
      "lists": {"l1": ["fruits", ["apple", 2, true]]},
      "broadcasts": {"b1": "ping"},
      "blocks": {
        "loose": [12, "score", "v1", 10, 20]
      },
      "comments": {"c1": {"blockId": null, "x": 1, "y": 2, "width": 200, "height": 100, "minimized": false, "text": "hi"}},
      "currentCostume": 0,
      "costumes": [{"name": "backdrop1", "bitmapResolution": 1, "dataFormat": "svg", "assetId": "cd21514d0531fdffb22204e0ec5ed84a",
                    "md5ext": "cd21514d0531fdffb22204e0ec5ed84a.svg", "rotationCenterX": 240, "rotationCenterY": 180}],
      "sounds": [{"name": "pop", "assetId": "83a9787d4cb6f3b7632b4ddfebf74367", "dataFormat": "wav", "format": "",
                  "rate": 48000, "sampleCount": 1123, "md5ext": "83a9787d4cb6f3b7632b4ddfebf74367.wav"}],
      "volume": 100, "layerOrder": 0, "tempo": 60, "videoTransparency": 50, "videoState": "on", "textToSpeechLanguage": null
    },
    {
      "isStage": false, "name": "Sprite1", "variables": {}, "lists": {}, "broadcasts": {},
      "blocks": {
        "hat": {"opcode": "event_whenflagclicked", "next": "say", "parent": null, "inputs": {}, "fields": {},
                "shadow": false, "topLevel": true, "x": 100, "y": 50, "comment": "c2"},
        "say": {"opcode": "looks_say", "next": "stop", "parent": "hat",
                "inputs": {"MESSAGE": [3, "add", [10, "Hello!"]]}, "fields": {}, "shadow": false, "topLevel": false},
        "add": {"opcode": "operator_add", "next": null, "parent": "say",
                "inputs": {"NUM1": [1, [4, "2"]], "NUM2": [3, [12, "score", "v1"], [4, 7]]},
                "fields": {}, "shadow": false, "topLevel": false},
        "stop": {"opcode": "control_stop", "next": null, "parent": "say", "inputs": {},
                 "fields": {"STOP_OPTION": ["all", null]}, "shadow": false, "topLevel": false,
                 "mutation": {"tagName": "mutation", "children": [], "hasnext": "false"}},
        "menu": {"opcode": "looks_costume", "next": null, "parent": "switch", "inputs": {},
                 "fields": {"COSTUME": ["costume1", null]}, "shadow": true, "topLevel": false},
        "switch": {"opcode": "looks_switchcostumeto", "next": null, "parent": null,
                   "inputs": {"COSTUME": [1, "menu"]}, "fields": {}, "shadow": false, "topLevel": true, "x": 0, "y": 300},
        "if": {"opcode": "control_if", "next": null, "parent": null,
               "inputs": {"CONDITION": [2, "eq"], "SUBSTACK": [1, null]}, "fields": {}, "shadow": false,
               "topLevel": true, "x": 0, "y": 500},
        "eq": {"opcode": "operator_equals", "next": null, "parent": "if",
               "inputs": {"OPERAND1": [1, [10, "a"]], "OPERAND2": [1, [10, "b"]]}, "fields": {}, "shadow": false,
               "topLevel": false}
      },
      "comments": {"c2": {"blockId": "hat", "x": 300, "y": 50, "width": 200, "height": 100, "minimized": true, "text": "flag"}},
      "currentCostume": 0,
      "costumes": [{"name": "costume1", "bitmapResolution": 2, "dataFormat": "png", "assetId": "bcf454acf82e4504149f7ffe07081dbc",
                    "md5ext": "bcf454acf82e4504149f7ffe07081dbc.png", "rotationCenterX": 48, "rotationCenterY": 50}],
      "sounds": [], "volume": 100, "layerOrder": 1, "visible": true, "x": 0, "y": 0, "size": 100, "direction": 90,
      "draggable": false, "rotationStyle": "all around"
    }
  ],
  "monitors": [
    {"id": "v1", "mode": "default", "opcode": "data_variable", "params": {"VARIABLE": "score"}, "spriteName": null,
     "value": 3, "width": 0, "height": 0, "x": 5, "y": 5, "visible": true, "sliderMin": 0, "sliderMax": 100, "isDiscrete": true},
    {"id": "l1", "mode": "list", "opcode": "data_listcontents", "params": {"LIST": "fruits"}, "spriteName": null,
     "value": ["apple", 2, true], "width": 120, "height": 200, "visible": false, "sliderMin": 0, "sliderMax": 100,
     "isDiscrete": true}
  ],
  "extensions": ["pen"],
  "meta": {"semver": "3.0.0", "vm": "2.3.0", "agent": "test", "platform": {"name": "TurboWarp"}}
}
"""

class ProjectRoundTripTest {
    @Test
    fun readsTheEncodings() {
        val project = ProjectReader.read(SAMPLE_PROJECT)
        val stage = project.stage
        assertEquals(3.0, stage.variables.getValue("v1").value)
        assertTrue(stage.variables.getValue("v2").isCloud)
        assertEquals(listOf("apple", 2.0, true), stage.lists.getValue("l1").items)
        assertEquals(setOf("loose"), stage.looseReporters.keys)
        assertEquals(setOf("c1"), stage.comments.keys)
        assertEquals(
            listOf("tempo", "videoTransparency", "videoState", "textToSpeechLanguage"),
            stage.extra.keys.toList(),
        )
        assertEquals(listOf("format", "rate", "sampleCount"), stage.sounds.single().extra.keys.toList())

        val sprite = project.sprites.single()
        val hat = sprite.blocks.getValue("hat")
        assertEquals("c2", hat.commentId)
        assertEquals(listOf("x", "y", "comment"), hat.extra.keys.toList())
        val message = assertIs<Input.BlockRef>(sprite.blocks.getValue("say").inputs.getValue("MESSAGE"))
        assertEquals("add", message.id)
        val shadow = assertIs<Input.Literal>(message.shadow)
        assertEquals("Hello!", shadow.value)
        assertNull(shadow.numberValue)
        val add = sprite.blocks.getValue("add")
        val num1 = assertIs<Input.Literal>(add.inputs.getValue("NUM1"))
        assertEquals("2", num1.value)
        assertNull(num1.numberValue)
        val num2 = assertIs<Input.VariableRef>(add.inputs.getValue("NUM2"))
        assertEquals("v1", num2.id)
        assertEquals(7.0, assertIs<Input.Literal>(num2.shadow).numberValue)
        val stop = sprite.blocks.getValue("stop")
        assertNull(stop.mutation)
        assertEquals("false", stop.mutationJson?.get("hasnext")?.asString)
        assertIs<Input.Empty>(sprite.blocks.getValue("if").inputs.getValue("SUBSTACK"))
        assertEquals(3.0, project.monitors[0].extra["value"]?.asDouble)
        assertTrue(project.monitors[1].x.isNaN())
        assertEquals("TurboWarp", project.extra["meta"]?.get("platform")?.get("name")?.asString)
    }

    @Test
    fun writesBackWhatItRead() {
        val original = JsonParser.parse(SAMPLE_PROJECT)
        val written = ProjectWriter.write(ProjectReader.read(original))
        assertEquals(original, written)
        // And reading the output again changes nothing more.
        assertEquals(written, ProjectWriter.write(ProjectReader.read(written)))
    }

    @Test
    fun encodesInputsMadeByAPass() {
        val project = ProjectReader.read(SAMPLE_PROJECT)
        val sprite = project.sprites.single()
        val say = sprite.blocks.getValue("say")
        say.inputs["MESSAGE"] = Input.Literal(10, "4", 4.0)
        val switch = sprite.blocks.getValue("switch")
        switch.inputs["COSTUME"] = Input.BlockRef("menu")
        switch.inputs["EXTRA"] = Input.BlockRef("add", Input.Literal(4, "1"))
        val ifBlock = sprite.blocks.getValue("if")
        ifBlock.inputs["CONDITION"] = Input.BlockRef("eq")
        ifBlock.inputs["SUBSTACK"] = Input.Empty()
        val written = ProjectWriter.write(project)
        val blocks = written["targets"]!![1]!!["blocks"]!!
        assertEquals("[1,[10,4]]", blocks["say"]!!["inputs"]!!["MESSAGE"].toString())
        assertEquals("[1,\"menu\"]", blocks["switch"]!!["inputs"]!!["COSTUME"].toString())
        assertEquals("[3,\"add\",[4,\"1\"]]", blocks["switch"]!!["inputs"]!!["EXTRA"].toString())
        assertEquals("[2,\"eq\"]", blocks["if"]!!["inputs"]!!["CONDITION"].toString())
        assertEquals("[1,null]", blocks["if"]!!["inputs"]!!["SUBSTACK"].toString())
    }
}

/**
 * Fails unless everything in [original] is also in [written] with the same value, so a writer
 * may add members with default values but never lose or change one. [path] names the place.
 */
internal fun assertContains(original: JsonValue, written: JsonValue?, path: String = "$") {
    when (original) {
        is JsonObject -> {
            val w = written?.asObject ?: fail("$path: expected an object, got $written")
            for ((k, v) in original.fields) assertContains(v, w.fields[k], "$path.$k")
        }
        is JsonArray -> {
            val w = written?.asArray ?: fail("$path: expected an array, got $written")
            assertEquals(original.size, w.size, "$path: length")
            for (i in original.items.indices) assertContains(original.items[i], w.items[i], "$path[$i]")
        }
        else -> assertEquals(original, written, path)
    }
}
