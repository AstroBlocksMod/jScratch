package org.jscratch.compiler.jvm

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ScriptableObject

/**
 * How jScratch runs the `sb3js` bundles in Rhino: ES6, in the interpreter (the bundles are
 * far bigger than one JVM method may be, and the interpreter has no such limit), and with
 * little-endian typed arrays. Rhino's typed arrays are big-endian unless asked otherwise,
 * while every browser is little-endian, and the Scratch code reads binary data through
 * typed arrays of different widths over one buffer.
 */
internal object Rhino : ContextFactory() {
    /** Stand-ins for the browser globals the bundles touch: a console for ajv, random bytes for uuid. */
    private const val PRELUDE = """
        var console = { log: function () {}, info: function () {}, warn: function () {}, error: function () {} };
        var crypto = {
          getRandomValues: function (array) {
            for (var i = 0; i < array.length; i++) array[i] = Math.floor(Math.random() * 256);
            return array;
          }
        };
    """

    override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
        featureIndex == Context.FEATURE_LITTLE_ENDIAN || super.hasFeature(cx, featureIndex)

    override fun onContextCreated(cx: Context) {
        super.onContextCreated(cx)
        cx.languageVersion = Context.VERSION_ES6
        cx.isInterpretedMode = true
    }

    /** Evaluates a bundle from the class path into a new scope that callers then reuse. */
    fun load(resource: String): ScriptableObject {
        val source =
            Rhino::class.java.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("$resource is missing from the class path")
        val cx = enterContext()
        try {
            val scope = cx.initSafeStandardObjects()
            cx.evaluateString(scope, PRELUDE, "prelude.js", 1, null)
            cx.evaluateString(scope, source, resource.substringAfterLast('/'), 1, null)
            return scope
        } finally {
            Context.exit()
        }
    }
}
