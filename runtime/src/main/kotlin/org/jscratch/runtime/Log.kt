package org.jscratch.runtime

/** Tiny logger. Each distinct message is printed once so unsupported blocks in a loop do not flood the console. */
object Log {
    private val seen = HashSet<String>()

    @JvmStatic
    fun warn(message: String) {
        synchronized(seen) {
            if (seen.add(message)) System.err.println("[jscratch] $message")
        }
    }

    @JvmStatic
    fun info(message: String) {
        System.err.println("[jscratch] $message")
    }
}
