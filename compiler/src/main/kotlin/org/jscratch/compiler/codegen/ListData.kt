package org.jscratch.compiler.codegen

/**
 * Text format for large list contents stored as JAR resources. One item per
 * line, prefixed with its type: `s` string, `n` number, `b` boolean. Strings
 * are escaped with [LineText]. The runtime's `ListData` decodes it; keeping the
 * format this small means the runtime needs no JSON.
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
                    LineText.escape(sb, item.toString())
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
