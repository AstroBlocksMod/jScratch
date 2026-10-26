package org.jscratch.compiler.jvm

import kotlinx.serialization.json.Json
import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.ProjectJson
import org.jscratch.compiler.sb3.Sb3Exception
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * JVM implementation of [ProjectJson]: plain JSON goes through
 * kotlinx-serialization, and `project.json` goes through scratch-parser running
 * inside Rhino. The scratch-parser bundle (built by the `sb3js` module) is
 * evaluated once into a scope that every call then reuses; Rhino contexts are
 * per thread, so calls are safe from any thread.
 *
 * The bundle hands the validated project back as a JSON string, which is the
 * cheapest thing to move across the Rhino boundary: Rhino's `JSON.stringify`
 * is native Java and kotlinx parses the result.
 */
class RhinoProjectJson : ProjectJson {
    private val scope: ScriptableObject = Rhino.load(BUNDLE)

    override fun parse(text: String): JsonValue = Json.parseToJsonElement(text).toTree()

    override fun parseProject(text: String): JsonValue {
        val cx = Rhino.enterContext()
        try {
            val module = ScriptableObject.getProperty(scope, "jscratchSb3") as Scriptable
            val fn = ScriptableObject.getProperty(module, "parseProjectToJson") as Function
            val result = fn.call(cx, scope, module, arrayOf(text))
            return parse(Context.toString(result))
        } catch (e: JavaScriptException) {
            throw Sb3Exception(e.details().removePrefix("Error: "))
        } catch (e: RhinoException) {
            throw Sb3Exception("scratch-parser failed: ${e.details()}")
        } finally {
            Context.exit()
        }
    }

    companion object {
        private const val BUNDLE = "/org/jscratch/compiler/jvm/jscratch-sb3.js"
    }
}
