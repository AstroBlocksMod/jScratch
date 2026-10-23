package org.jscratch.web

import org.jscratch.compiler.JScratch
import org.teavm.jso.JSBody
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject

/**
 * Browser entry point. TeaVM compiles the compiler module to JavaScript and this
 * file exposes it as `window.jscratch.compile(sb3, runtimeJar, listing)`.
 * Primitive arrays cross the boundary as typed arrays, so the page passes
 * `Int8Array`s and gets the JAR back as one. The page must load the
 * scratch-parser bundle (`jscratch-sb3.js`) first; see [BrowserProjectJson].
 */
@JSFunctor
fun interface CompileFunction : JSObject {
    fun compile(sb3: ByteArray, runtimeJar: ByteArray?, listing: Boolean): JSObject
}

@JSBody(params = ["fn"], script = "window.jscratch = { compile: fn };")
external fun install(fn: CompileFunction)

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
    )
}
