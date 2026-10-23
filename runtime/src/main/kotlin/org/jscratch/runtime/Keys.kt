package org.jscratch.runtime

import java.awt.event.KeyEvent

/** Maps between AWT key events and Scratch key names ("space", "left arrow", "A", "1"). */
object Keys {
    fun fromEvent(e: KeyEvent): String? = when (e.keyCode) {
        KeyEvent.VK_SPACE -> "space"
        KeyEvent.VK_LEFT -> "left arrow"
        KeyEvent.VK_RIGHT -> "right arrow"
        KeyEvent.VK_UP -> "up arrow"
        KeyEvent.VK_DOWN -> "down arrow"
        KeyEvent.VK_ENTER -> "enter"
        else -> {
            val c = e.keyChar
            if (c != KeyEvent.CHAR_UNDEFINED && !c.isISOControl()) c.uppercaseChar().toString() else null
        }
    }

    /** Normalizes a `key pressed?` argument the way scratch-vm does. */
    @JvmStatic
    fun normalize(key: Any?): String {
        if (key is Double) {
            val code = key.toInt()
            if (code in 48..90) return code.toChar().uppercaseChar().toString()
            when (code) {
                32 -> return "space"
                37 -> return "left arrow"
                38 -> return "up arrow"
                39 -> return "right arrow"
                40 -> return "down arrow"
                13 -> return "enter"
            }
        }

        val s = Cast.toString(key)
        if (s.length == 1) return s.uppercase()
        return when (s) {
            " " -> "space"
            else -> s
        }
    }
}
