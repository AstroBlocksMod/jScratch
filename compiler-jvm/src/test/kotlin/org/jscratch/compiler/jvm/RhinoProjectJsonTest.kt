package org.jscratch.compiler.jvm

import org.jscratch.compiler.json.JsonArray
import org.jscratch.compiler.json.JsonObject
import org.jscratch.compiler.sb3.Sb3Exception
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RhinoProjectJsonTest {
    private val json = RhinoProjectJson()

    @Test
    fun plainJsonGoesThroughKotlinx() {
        val v = json.parse("""{"a": [1, 2.5, "x\nA", true, null], "b": {"c": -3e2}}""")
        val a = v["a"] as JsonArray
        assertEquals(5, a.size)
        assertEquals(1.0, a[0]?.asDouble)
        assertEquals(2.5, a[1]?.asDouble)
        assertEquals("x\nA", a[2]?.asString)
        assertEquals(true, a[3]?.asBoolean)
        assertTrue(a[4]!!.isNull)
        assertEquals(-300.0, v["b"]!!["c"]?.asDouble)
        assertTrue(v is JsonObject)
    }

    @Test
    fun validProjectPassesScratchParser() {
        val project =
            """
            {"targets":[{"isStage":true,"name":"Stage","variables":{},"lists":{},"broadcasts":{},"blocks":{},
            "currentCostume":0,"costumes":[{"name":"b","dataFormat":"svg","assetId":"0123456789abcdef0123456789abcdef",
            "md5ext":"0123456789abcdef0123456789abcdef.svg",
            "rotationCenterX":0,"rotationCenterY":0}],"sounds":[],"volume":100,"layerOrder":0}],
            "monitors":[],"extensions":[],"meta":{"semver":"3.0.0","vm":"0.2.0","agent":"test"}}
            """.trimIndent()
        val tree = json.parseProject(project)
        assertEquals("Stage", tree["targets"]!![0]!!["name"]?.asString)
        assertEquals(3.0, tree["projectVersion"]?.asDouble, "scratch-parser tags validated projects with a version")
    }

    @Test
    fun invalidProjectIsRejectedWithTheSchemaError() {
        val e = assertFailsWith<Sb3Exception> { json.parseProject("""{"targets": "nope"}""") }
        assertTrue(e.message!!.contains("valid"), e.message)
    }

    @Test
    fun brokenJsonIsRejected() {
        val e = assertFailsWith<Sb3Exception> { json.parseProject("{") }
        assertTrue(e.message!!.contains("JSON"), e.message)
    }
}
