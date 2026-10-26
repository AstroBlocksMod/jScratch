package org.jscratch.runtime

/**
 * Decodes the list resources the compiler writes for large initial list
 * contents: one item per line, prefixed with `s` (string, escaped as [LineText]
 * describes), `n` (number) or `b` (boolean).
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
                else -> out.add(LineText.unescape(body))
            }
        }
        return out
    }
}
