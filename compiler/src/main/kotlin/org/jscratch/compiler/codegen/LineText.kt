package org.jscratch.compiler.codegen

/**
 * Escaping for the line formats the compiler stores as JAR resources (large list contents and the monitor
 * table): backslash, tab, newline and carriage return become `\\`, `\t`, `\n` and `\r`, so every record
 * fits on one line and tabs can separate fields. The runtime's `LineText` undoes it.
 */
object LineText {
    fun escape(sb: StringBuilder, s: String) {
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
    }
}
