package org.jscratch.compiler.codegen

/** Turns Scratch names (anything goes) into readable JVM identifiers, unique within a scope. */
class Names {
    private val used = HashSet<String>()

    fun unique(prefix: String, raw: String): String {
        val base = prefix + sanitize(raw)
        var candidate = base
        var n = 2
        while (!used.add(candidate)) {
            candidate = "${base}_${n++}"
        }
        return candidate
    }

    companion object {
        fun sanitize(raw: String): String {
            val sb = StringBuilder()
            for (c in raw) {
                when {
                    c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                    c == ' ' || c == '-' || c == '_' -> sb.append('_')
                    else -> {}
                }
            }

            var s = sb.toString().trim('_')
            if (s.isEmpty()) s = "x"
            if (s[0].isDigit()) s = "_$s"
            return if (s.length > 40) s.substring(0, 40) else s
        }
    }
}

/** Owner classes and descriptors of the runtime API the generated code calls. */
object RT {
    const val PACKAGE = "jscratch/project"
    const val MAIN = "$PACKAGE/Main"
    const val STAGE_CLASS = "$PACKAGE/Stage"

    const val RUNTIME = "org/jscratch/runtime/Runtime"
    const val TARGET = "org/jscratch/runtime/Target"
    const val SPRITE = "org/jscratch/runtime/Sprite"
    const val STAGE = "org/jscratch/runtime/Stage"
    const val CAST = "org/jscratch/runtime/Cast"
    const val OPS = "org/jscratch/runtime/Ops"
    const val LIST = "org/jscratch/runtime/ScratchList"

    const val OBJECT = "java/lang/Object"
    const val STRING = "java/lang/String"
    const val D_OBJECT = "Ljava/lang/Object;"
    const val D_STRING = "Ljava/lang/String;"
    const val D_LIST = "Lorg/jscratch/runtime/ScratchList;"
    const val D_RUNTIME = "Lorg/jscratch/runtime/Runtime;"
    const val D_TARGET = "Lorg/jscratch/runtime/Target;"

    const val HAT_FLAG = 0
    const val HAT_KEY = 1
    const val HAT_CLICKED = 2
    const val HAT_BROADCAST = 3
    const val HAT_BACKDROP = 4
    const val HAT_CLONE_START = 5
}
