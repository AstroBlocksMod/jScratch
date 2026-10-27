package org.jscratch.runtime

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import javax.imageio.ImageIO

/**
 * Scripted input from the file named by `-Djscratch.input`, for driving a project through its menus in
 * tests and while debugging, in a window or headless. Each line is `<frame> <command> <arguments>` and
 * runs once that many frames have run, so its input reaches the next frame. Blank lines and lines
 * starting with `#` are skipped. Coordinates are stage coordinates, with (0, 0) in the middle.
 *
 * - `move <x> <y>`, `down <x> <y>`, `up <x> <y>`: move the mouse, press or release it there.
 * - `click <x> <y>`: press there and release two frames later wherever the mouse is then, so scripts
 *   polling `mouse down?` see it.
 * - `key <name>`, `keyup <name>`: press and hold a key, or release it. Names are Scratch's: `space`,
 *   `left arrow`, `a`.
 * - `flag`, `stop`: the green flag and the stop button.
 * - `screenshot <file.png>`: save the stage as it is now, between two frames, at twice its size.
 * - `window <file.png>`: in a window, save what the window paints, at twice its size, when Swing next
 *   gets to it, the same way it repaints the screen while the scheduler goes on with the next frame.
 * - `pause <ms>`: wait before starting the next frame.
 * - `quit`: end the run, as closing the window does.
 *
 * A step that fails ends the run with an error naming its line, and so does a `window` capture that
 * fails after its frame went on.
 */
class InputScript private constructor(steps: List<Step>) {
    private class Step(val frame: Long, val line: Int, val command: String, val action: Action)

    private val steps = ArrayList(steps.sortedBy { it.frame })
    private var next = 0

    /** `window` captures still being painted or written, with the line that asked for each. */
    private val writes = ArrayList<Pair<Int, CompletableFuture<*>>>()

    /** Whether some step has not run yet. Keeps a headless run going while there is input to come. */
    val pending: Boolean get() = next < steps.size

    /** Runs every step that is due once [Runtime.frameCount] frames have run, until one quits. */
    fun runDue(rt: Runtime) {
        checkWrites(wait = false)
        while (rt.running && next < steps.size && steps[next].frame <= rt.frameCount) {
            val step = steps[next++]
            try {
                step.action(this, rt, step.line)
            } catch (e: Exception) {
                throw failure(step.line, step.command, e)
            }
        }
    }

    /** Waits for the `window` captures still being written, so the run ends with them on disk or fails. */
    fun finish() {
        checkWrites(wait = true)
    }

    private fun checkWrites(wait: Boolean) {
        val it = writes.iterator()
        while (it.hasNext()) {
            val (line, write) = it.next()
            if (!wait && !write.isDone) continue
            it.remove()
            try {
                write.join()
            } catch (e: CompletionException) {
                throw failure(line, "window", e.cause ?: e)
            }
        }
    }

    private fun schedule(step: Step) {
        var i = next
        while (i < steps.size && steps[i].frame <= step.frame) i++
        steps.add(i, step)
    }

    private fun failure(line: Int, command: String, e: Throwable) =
        IllegalStateException("jscratch.input line $line ($command): ${e.message ?: e}", e)

    private class Command(val arity: IntRange, val build: (List<String>) -> Action)

    companion object {
        private val REST = 1..Int.MAX_VALUE

        private val COMMANDS: Map<String, Command> =
            mapOf(
                "move" to mouse(null),
                "down" to mouse(true),
                "up" to mouse(false),
                "click" to
                    Command(2..2) { a ->
                        val (x, y) = numbers(a)
                        action { rt, line ->
                            rt.postMouse(x, y, true)
                            val release = action { r, _ -> r.postMouse(r.mouseX, r.mouseY, false) }
                            schedule(Step(rt.frameCount + 2, line, "click", release))
                        }
                    },
                "key" to
                    Command(REST) { a ->
                        val key = Keys.normalize(a.joinToString(" "))
                        action { rt, _ -> rt.postKeyDown(key) }
                    },
                "keyup" to
                    Command(REST) { a ->
                        val key = Keys.normalize(a.joinToString(" "))
                        action { rt, _ -> rt.postKeyUp(key) }
                    },
                "flag" to Command(0..0) { action { rt, _ -> rt.postGreenFlag() } },
                "stop" to Command(0..0) { action { rt, _ -> rt.postStop() } },
                "screenshot" to
                    Command(REST) { a ->
                        val file = File(a.joinToString(" "))
                        action { rt, _ -> rt.saveScreenshot(file) }
                    },
                "window" to
                    Command(REST) { a ->
                        val file = File(a.joinToString(" "))
                        action { rt, line ->
                            val w = rt.window ?: error("there is no window in headless mode")
                            // Encode the PNG off the event thread, so saving it does not hold up painting and input.
                            writes.add(line to w.capture().thenAcceptAsync { img -> ImageIO.write(img, "png", file) })
                        }
                    },
                "pause" to
                    Command(1..1) { a ->
                        val ms = a[0].toLongOrNull()?.takeIf { it >= 0 }
                        requireNotNull(ms) { "expected a number of milliseconds" }
                        action { _, _ -> Thread.sleep(ms) }
                    },
                "quit" to
                    Command(0..0) {
                        action { rt, _ ->
                            rt.running = false
                            rt.window?.close()
                        }
                    },
            )

        /** Spells out a lambda's type where Kotlin would otherwise read it as the previous line's trailing lambda. */
        private fun action(block: Action): Action = block

        private fun mouse(down: Boolean?) = Command(2..2) { a ->
            val (x, y) = numbers(a)
            action { rt, _ -> rt.postMouse(x, y, down) }
        }

        private fun numbers(args: List<String>): List<Double> = args.mapIndexed { i, s ->
            s.toDoubleOrNull() ?: throw IllegalArgumentException("expected a number as argument ${i + 1}")
        }

        /** Reads the file named by `-Djscratch.input`, if there is one. */
        fun fromProperty(): InputScript? = System.getProperty("jscratch.input")?.let { parse(File(it).readText()) }

        /** Parses a script, failing on the first line it cannot run so a typo does not go unnoticed. */
        fun parse(text: String): InputScript {
            val steps = ArrayList<Step>()
            for ((index, raw) in text.lines().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                fun fail(message: String): Nothing =
                    throw IllegalArgumentException("jscratch.input line ${index + 1}: $message")
                val words = line.split(Regex("\\s+"))
                val frame = words[0].toLongOrNull()?.takeIf { it >= 0 } ?: fail("expected a frame number")
                val name = words.getOrNull(1) ?: fail("expected a command after the frame number")
                val command = COMMANDS[name] ?: fail("unknown command '$name'")
                val args = words.drop(2)
                if (args.size !in command.arity) fail("wrong number of arguments for $name")
                val action =
                    try {
                        command.build(args)
                    } catch (e: IllegalArgumentException) {
                        fail("${e.message} for $name")
                    }
                steps.add(Step(frame, index + 1, name, action))
            }
            return InputScript(steps)
        }
    }
}

/** What a step does when it runs. It gets its script, to schedule more steps, and the step's line. */
private typealias Action = InputScript.(Runtime, Int) -> Unit
