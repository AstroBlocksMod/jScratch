package org.jscratch.web

import org.jscratch.compiler.Archives
import org.jscratch.compiler.JScratch
import org.jscratch.compiler.convert.OldProject
import org.jscratch.compiler.convert.OldProjects
import org.teavm.jso.JSBody
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject

/**
 * Browser entry point. TeaVM compiles the compiler module to JavaScript and this
 * file exposes it as `window.jscratch`:
 *
 * - `compile(sb3, runtimeJar, listing)` compiles an .sb3.
 * - `readOld(data)` reads a Scratch 1.4 or 2 project for the converter bundle
 *   (`jscratch-convert.js`), or returns null for anything else.
 * - `writeSb3(projectJson, names, datas)` packs the converter's result into an .sb3.
 *
 * The converter returns a promise, which TeaVM code cannot wait for, so the page
 * calls it between `readOld` and `writeSb3`. Primitive arrays cross the boundary
 * as typed arrays, so the page passes `Int8Array`s and gets them back. The page
 * must load the scratch-parser bundle (`jscratch-sb3.js`) first; see
 * [BrowserProjectJson].
 */
@JSFunctor
fun interface CompileFunction : JSObject {
    fun compile(sb3: ByteArray, runtimeJar: ByteArray?, listing: Boolean): JSObject
}

@JSFunctor
fun interface ReadOldFunction : JSObject {
    fun readOld(data: ByteArray): JSObject?
}

@JSFunctor
fun interface WriteSb3Function : JSObject {
    fun writeSb3(projectJson: String, names: JSObject, datas: JSObject): ByteArray
}

@JSBody(
    params = ["compile", "readOld", "writeSb3"],
    script = "window.jscratch = { compile: compile, readOld: readOld, writeSb3: writeSb3 };",
)
external fun install(compile: CompileFunction, readOld: ReadOldFunction, writeSb3: WriteSb3Function)

@JSBody(
    params = ["format", "label", "projectJson", "names", "datas"],
    script = "return { format: format, label: label, projectJson: projectJson, names: names, datas: datas };",
)
external fun oldProject(format: String, label: String, projectJson: String?, names: JSObject, datas: JSObject): JSObject

@JSBody(script = "return [];")
external fun newArray(): JSObject

@JSBody(params = ["array", "value"], script = "array.push(value);")
external fun pushString(array: JSObject, value: String)

@JSBody(params = ["array", "value"], script = "array.push(value);")
external fun pushBytes(array: JSObject, value: ByteArray)

@JSBody(params = ["array"], script = "return array.length;")
external fun arrayLength(array: JSObject): Int

@JSBody(params = ["array", "i"], script = "return array[i];")
external fun stringAt(array: JSObject, i: Int): String

@JSBody(params = ["array", "i"], script = "return array[i];")
external fun bytesAt(array: JSObject, i: Int): ByteArray

@JSBody(
    params = ["jar", "listing", "warnings", "classes", "targets"],
    script = "return { ok: true, jar: jar, listing: listing, warnings: warnings, classes: classes, targets: targets };",
)
external fun success(jar: ByteArray, listing: String, warnings: String, classes: String, targets: String): JSObject

@JSBody(params = ["message"], script = "return { ok: false, error: message };")
external fun failure(message: String): JSObject

fun main() {
    install(
        object : CompileFunction {
            override fun compile(sb3: ByteArray, runtimeJar: ByteArray?, listing: Boolean): JSObject = try {
                val result = JScratch.compile(sb3, runtimeJar, BrowserProjectJson)
                val text =
                    if (listing) {
                        result.classes.entries.joinToString("\n") { (name, bytes) ->
                            "// ==== $name\n" + JScratch.disassemble(bytes)
                        }
                    } else {
                        ""
                    }
                success(
                    result.jar,
                    text,
                    result.warnings.joinToString("\n"),
                    result.classes.keys.joinToString("\n"),
                    result.project.targets.joinToString("\n") { t ->
                        "${t.name}\t${if (t.isStage) "stage" else "sprite"}\t${t.blocks.size}\t${t.costumes.size}"
                    },
                )
            } catch (t: Throwable) {
                failure(t.message ?: t.toString())
            }
        },
        ReadOldFunction { data -> readOld(data) },
        WriteSb3Function { projectJson, names, datas ->
            val assets = LinkedHashMap<String, ByteArray>()
            for (i in 0 until arrayLength(names)) assets[stringAt(names, i)] = bytesAt(datas, i)
            Archives.writeProject(projectJson.toByteArray(Charsets.UTF_8), assets)
        },
    )
}

/** An object the page hands to the converter bundle: `{ format, label, projectJson, names, datas }`. */
private fun readOld(data: ByteArray): JSObject? {
    val project =
        try {
            OldProjects.read(data, BrowserProjectJson)
        } catch (t: Throwable) {
            // Not an archive the converter could read either; compiling reports what is wrong.
            null
        } ?: return null
    val names = newArray()
    val datas = newArray()
    return when (project) {
        is OldProject.Sb1 -> {
            pushBytes(datas, project.data)
            oldProject("sb1", project.format.label, null, names, datas)
        }
        is OldProject.Sb2 -> {
            for ((name, bytes) in project.files) {
                pushString(names, name)
                pushBytes(datas, bytes)
            }
            oldProject("sb2", project.format.label, project.projectJson, names, datas)
        }
    }
}
