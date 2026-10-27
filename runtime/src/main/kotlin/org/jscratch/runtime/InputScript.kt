package org.jscratch.runtime

import java.io.File
import javax.imageio.ImageIO

/**
 * Scripted input from the file named by `-Djscratch.input`, for driving a project through its menus in
 * tests and while debugging, in a window or headless. Each line is `<frame> <command> <arguments>` and
 * runs once that many frames have run, so its input reaches the next frame. Blank lines and lines
 * starting with `#` are skipped. Coordinates are stage coordinates, with (0, 0) in the middle.
 *
 * - `move <x> <y>`, `down <x> <y>`, `up <x> <y>`: move the mouse, press or release it there.
 * - `click <x> <y>`: press there and release two frames later, so scripts polling `mouse down?` see it.
 * - `key <name>`, `keyup <name>`: press and hold a key, or release it. Names are Scratch's: `space`,
 *   `left arrow`, `a`.
 * - `flag`, `stop`: the green flag and the stop button.
 * - `screenshot <file.png>`: save the stage as it is now, between two frames, at twice its size.
 * - `window <file.png>`: in a window, save what the window paints, at twice its size, when Swing next
 *   gets to it, the same way it repaints the screen while the scheduler goes on with the next frame.
 * - `pause <ms>`: wait before starting the next frame.
 * - `quit`: end the run, as closing the window does.
 */
class InputScript(steps: List<Step>) {
    class Step(val frame: Long, val line: Int, val command: String, val args: List<String>)

    private val steps = ArrayList(steps.sortedBy { it.frame })
    private var next = 0

    /** Whether some step has not run yet. Keeps a headless run going while there is input to come. */
    val pending: Boolean get() = next < steps.size

    /** Runs every step that is due once [Runtime.frameCount] frames have run. */
    fun runDue(rt: Runtime) {
        while (next < steps.size && steps[next].frame <= rt.frameCount) {
            val step = steps[next++]
            try {
                run(rt, step)
            } catch (e: Exception) {
                throw IllegalStateException("jscratch.input line ${step.line} (${step.command}): ${e.message}", e)
            }
        }
    }

    private fun run(rt: Runtime, step: Step) {
        val a = step.args
        when (step.command) {
            "move" -> rt.postMouse(a.number(0), a.number(1), null)
            "down" -> rt.postMouse(a.number(0), a.number(1), true)
            "up" -> rt.postMouse(a.number(0), a.number(1), false)
            "click" -> {
                rt.postMouse(a.number(0), a.number(1), true)
                insert(Step(rt.frameCount + 2, step.line, "up", a))
            }
            "key" -> rt.postKeyDown(Keys.normalize(a.joinToString(" ")))
            "keyup" -> rt.postKeyUp(Keys.normalize(a.joinToString(" ")))
            "flag" -> rt.postGreenFlag()
            "stop" -> rt.postStop()
            "screenshot" -> {
                val img = StageRenderer.toImage(StageRenderer.snapshot(rt), rt.pen.image, 2)
                ImageIO.write(img, "png", File(a.joinToString(" ")))
            }
            "window" -> {
                val w = rt.window ?: error("there is no window in headless mode")
                w.capture(File(a.joinToString(" ")))
            }
            "pause" -> Thread.sleep(a.number(0).toLong())
            "quit" -> {
                rt.running = false
                rt.window?.close()
            }
        }
    }

    private fun insert(step: Step) {
        var i = next
        while (i < steps.size && steps[i].frame <= step.frame) i++
        steps.add(i, step)
    }

    private fun List<String>.number(i: Int): Double =
        getOrNull(i)?.toDoubleOrNull() ?: throw IllegalArgumentException("expected a number as argument ${i + 1}")

    companion object {
        private val COMMANDS =
            mapOf(
                "move" to 2, "down" to 2, "up" to 2, "click" to 2, "key" to -1, "keyup" to -1, "flag" to 0,
                "stop" to 0, "screenshot" to -1, "window" to -1, "pause" to 1, "quit" to 0,
            )

        /** Reads the file named by `-Djscratch.input`, if there is one. */
        fun fromProperty(): InputScript? = System.getProperty("jscratch.input")?.let { parse(File(it).readText()) }

        /** Parses a script, failing on the first line it cannot run so a typo does not go unnoticed. */
        fun parse(text: String): InputScript {
            val steps = ArrayList<Step>()
            for ((index, raw) in text.lines().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val words = line.split(Regex("\\s+"))
                val frame =
                    words[0].toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw IllegalArgumentException("jscratch.input line ${index + 1}: expected a frame number")
                val command = words.getOrNull(1)
                val arity =
                    COMMANDS[command]
                        ?: throw IllegalArgumentException(
                            "jscratch.input line ${index + 1}: unknown command '$command'",
                        )
                val args = words.drop(2)
                val ok = if (arity < 0) args.isNotEmpty() else args.size == arity
                require(ok) { "jscratch.input line ${index + 1}: wrong number of arguments for $command" }
                steps.add(Step(frame, index + 1, command!!, args))
            }
            return InputScript(steps)
        }
    }
}
