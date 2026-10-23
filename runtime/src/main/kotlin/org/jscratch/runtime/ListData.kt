package org.jscratch.runtime

/**
 * Decodes the list resources the compiler writes for large initial list
 * contents: one item per line, prefixed with `s` (string, with `\\`, `\n` and
 * `\r` escapes), `n` (number) or `b` (boolean).
 */
internal object ListData {
    fun decode(text: String): List<Any> {
        val out = ArrayList<Any>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val body = line.substring(1)
            when (line[0]) {
                'n' -> out.add(body.toDoubleOrNull() ?: 0.0)
                'b' -> out.add(body == "true")
                else -> out.add(unescape(body))
            }
        }
        return out
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
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
