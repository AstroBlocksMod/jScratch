package org.jscratch.runtime

/**
 * Undoes the escaping of the line formats the compiler stores as resources (large list contents and the
 * monitor table): `\\`, `\t`, `\n` and `\r` become backslash, tab, newline and carriage return. Any other
 * escaped character stands for itself, and a lone trailing backslash is kept.
 */
internal object LineText {
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
