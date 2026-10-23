package org.jscratch.compiler.codegen

/**
 * Text format for large list contents stored as JAR resources. One item per
 * line, prefixed with its type: `s` string, `n` number, `b` boolean. Strings
 * escape backslash, newline and carriage return. The runtime's `ListData`
 * decodes it; keeping the format this small means the runtime needs no JSON.
 */
object ListData {
    fun encode(items: List<Any>): String {
        val sb = StringBuilder()
        for (item in items) {
            when (item) {
                is Double -> sb.append('n').append(item.toString())
                is Boolean -> sb.append('b').append(item.toString())
                else -> {
                    sb.append('s')
                    for (c in item.toString()) {
                        when (c) {
                            '\\' -> sb.append("\\\\")
                            '\n' -> sb.append("\\n")
                            '\r' -> sb.append("\\r")
                            else -> sb.append(c)
                        }
                    }
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
