package org.jscratch.compiler.jvm

import org.jscratch.compiler.convert.ConvertedProject
import org.jscratch.compiler.convert.OldProject
import org.jscratch.compiler.convert.ProjectConverter
import org.jscratch.compiler.sb3.Sb3Exception
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeArrayBufferView

/**
 * JVM implementation of [ProjectConverter]: runs the converter bundle (`jscratch-convert.js`
 * from the `sb3js` module, which holds scratch-sb1-converter and scratch-vm's serializers) in
 * Rhino. The bundle is loaded on the first conversion, so compiling .sb3 files never pays for
 * it.
 *
 * The bundle returns a promise. Nothing in it waits for a timer or the network, so the
 * promise settles once Rhino has run the queued microtasks. Conversions share one scope and
 * the bundle keeps its warnings in a module, so they run one at a time.
 */
class RhinoProjectConverter : ProjectConverter {
    private val scope: ScriptableObject by lazy { Rhino.load(BUNDLE) }

    @Synchronized
    override fun convert(project: OldProject): ConvertedProject {
        val scope = scope
        val cx = Rhino.enterContext()
        try {
            val module = ScriptableObject.getProperty(scope, "jscratchConvert") as Scriptable
            val promise =
                when (project) {
                    is OldProject.Sb1 -> call(cx, module, "convertSb1", uint8Array(cx, project.data))
                    is OldProject.Sb2 -> {
                        val names = project.files.keys.toTypedArray<Any>()
                        val datas = project.files.values.map { uint8Array(cx, it) }.toTypedArray<Any>()
                        call(
                            cx,
                            module,
                            "convertSb2",
                            project.projectJson,
                            cx.newArray(scope, names),
                            cx.newArray(scope, datas),
                        )
                    }
                }
            val result = settle(cx, promise, project)
            val names = (result.get("names", result) as List<*>).map { it.toString() }
            val datas = (result.get("datas", result) as List<*>).map { bytes(it as NativeArrayBufferView) }
            val warnings = (result.get("warnings", result) as List<*>).map { it.toString() }
            return ConvertedProject(
                projectJson = Context.toString(result.get("projectJson", result)),
                assets = names.zip(datas).toMap(LinkedHashMap()),
                warnings = warnings,
            )
        } catch (e: RhinoException) {
            throw failed(project, e.details())
        } finally {
            Context.exit()
        }
    }

    private fun call(cx: Context, module: Scriptable, name: String, vararg args: Any): Scriptable {
        val fn = ScriptableObject.getProperty(module, name) as Function
        return fn.call(cx, scope, module, arrayOf(*args)) as Scriptable
    }

    /** Runs the microtasks until [promise] settles and returns what it resolved to. */
    private fun settle(cx: Context, promise: Scriptable, project: OldProject): Scriptable {
        var value: Any? = null
        var error: Any? = null
        var settled = false
        val then = ScriptableObject.getProperty(promise, "then") as Function
        then.call(
            cx,
            scope,
            promise,
            arrayOf(
                callback {
                    value = it
                    settled = true
                },
                callback {
                    error = it
                    settled = true
                },
            ),
        )
        cx.processMicrotasks()
        if (!settled) throw failed(project, "the converter did not finish")
        error?.let { throw failed(project, message(it)) }
        return value as Scriptable
    }

    private fun callback(body: (Any?) -> Unit) = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            body(args.getOrNull(0))
            return Context.getUndefinedValue()
        }
    }

    private fun message(error: Any?): String {
        if (error is Scriptable) {
            val message = ScriptableObject.getProperty(error, "message")
            if (message is CharSequence) return message.toString()
        }
        return Context.toString(error)
    }

    private fun failed(project: OldProject, reason: String) =
        Sb3Exception("could not convert the ${project.format.label} project: ${reason.removePrefix("Error: ")}")

    private fun uint8Array(cx: Context, data: ByteArray): Scriptable {
        val buffer = cx.newObject(scope, "ArrayBuffer", arrayOf(data.size)) as NativeArrayBuffer
        System.arraycopy(data, 0, buffer.buffer, 0, data.size)
        return cx.newObject(scope, "Uint8Array", arrayOf(buffer))
    }

    private fun bytes(view: NativeArrayBufferView): ByteArray =
        view.buffer.buffer.copyOfRange(view.byteOffset, view.byteOffset + view.byteLength)

    companion object {
        private const val BUNDLE = "/org/jscratch/compiler/jvm/jscratch-convert.js"
    }
}
