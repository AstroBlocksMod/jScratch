package org.jscratch.runtime

import java.util.concurrent.Semaphore

/** Thrown inside a script to unwind it when the script is stopped. */
class StopScript : RuntimeException(null, null, false, false)

/**
 * One running Scratch script.
 *
 * Scratch scripts are cooperative green threads: a script runs until it reaches
 * a yield point (end of a loop iteration, a wait, a glide), then the next script
 * runs. jScratch maps each script onto a JVM virtual thread. Exactly one script
 * runs at any time, and the scheduler decides the order: a yielding script asks
 * [Runtime.nextToRun] which script comes next, hands control straight to it and
 * parks until it is resumed. When the yielding script is the only one left to
 * step in the frame, it gets itself back and no thread switch happens at all.
 * The scheduler thread only takes part at frame boundaries. This keeps generated
 * bytecode simple: a `forever` loop really is a loop with a call to
 * `yieldLoop()` at the end.
 */
class ScriptThread(
    val runtime: Runtime,
    val target: Target,
    val scriptId: Int,
    /** Whether the script starts with an edge-activated hat, whose condition decides whether it runs at all. */
    private val edgeHat: Boolean = false,
) {
    // Made in [start], so a hat that does not fire allocates nothing but this object.
    private lateinit var thread: Thread
    private lateinit var permit: Semaphore
    private var launched = false

    @Volatile var done: Boolean = false
        private set

    @Volatile var killed: Boolean = false

    /** Set when the script yielded until the next frame (`wait`, `glide`) instead of a loop yield. */
    var tickYield: Boolean = false

    var warpDepth: Int = 0
    var warpStartNanos: Long = 0

    /** Whether the scheduler has picked the script once, so [start] has decided whether it runs. */
    var started: Boolean = false
        private set

    /**
     * Prepares the virtual thread the first time the scheduler picks the script, and tells whether the
     * script runs at all. An edge-activated hat checks its condition here, at the point in the frame
     * where scratch-vm checks it, so a hat that does not fire, which is most of them in most frames,
     * never needs a thread.
     */
    internal fun start(): Boolean {
        started = true
        if (killed || (edgeHat && !target.edgeActivated(scriptId))) {
            done = true
            return false
        }
        permit = Semaphore(0)
        thread = Thread.ofVirtual().name("script-${target.name}-$scriptId").unstarted(::run)
        return true
    }

    private fun run() {
        try {
            target.runScript(scriptId)
        } catch (_: StopScript) {
            // normal termination
        } catch (t: Throwable) {
            Log.warn("Script $scriptId in '${target.name}' crashed: $t")
            t.printStackTrace()
        } finally {
            done = true
            try {
                handOff()
            } catch (t: Throwable) {
                // The frame must end even if choosing the next script failed, or the scheduler waits forever.
                Log.warn("Scheduling after script $scriptId in '${target.name}' failed: $t")
                t.printStackTrace()
                runtime.endFrame()
            }
        }
    }

    /**
     * Gives control to this script. The scheduler calls it for the first script of a frame, and the
     * yielding script for every other one. The first time it starts the virtual thread, which is a
     * handoff by itself; after that the thread is parked in [handOff] and gets its permit.
     */
    internal fun resume() {
        if (launched) {
            permit.release()
        } else {
            launched = true
            thread.start()
        }
    }

    /**
     * Hands control to whatever the scheduler picks next, or back to the scheduler when the frame is
     * over, and parks until this script is resumed. Returns at once when the scheduler picks this
     * script again, which is what happens when no other script is left to step in the frame.
     */
    private fun handOff() {
        val next = runtime.nextToRun()
        if (next === this) return
        if (next == null) runtime.endFrame() else next.resume()
        if (!done) permit.acquireUninterruptibly()
    }

    /** Called from inside the script: lets the other scripts run. */
    fun yieldNow(untilNextTick: Boolean) {
        if (warpDepth > 0) {
            // "Run without screen refresh": keep going, but never hog the frame for more than 500 ms.
            if (System.nanoTime() - warpStartNanos < WARP_TIME_NANOS) {
                if (killed) throw StopScript()
                return
            }
            tickYield = true
            handOff()
            warpStartNanos = System.nanoTime()
            if (killed) throw StopScript()
            return
        }
        tickYield = untilNextTick
        handOff()
        if (killed) throw StopScript()
    }

    /** Called from inside the script: waits for the next frame, in warp mode too. */
    fun yieldFrame() {
        tickYield = true
        handOff()
        if (warpDepth > 0) warpStartNanos = System.nanoTime()
        if (killed) throw StopScript()
    }

    fun enterWarp() {
        if (warpDepth == 0) warpStartNanos = System.nanoTime()
        warpDepth++
    }

    fun exitWarp() {
        if (warpDepth > 0) warpDepth--
    }

    companion object {
        private const val WARP_TIME_NANOS = 500_000_000L
    }
}
