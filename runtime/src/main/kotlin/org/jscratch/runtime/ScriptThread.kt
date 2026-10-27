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
 * [Runtime.dispatch] to hand control to whichever script comes next and parks
 * until it is resumed. When the yielding script is the only one left to step in
 * the frame, it gets itself back and no thread switch happens at all. The
 * scheduler thread only takes part at frame boundaries. This keeps generated
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
    /**
     * What the script's virtual thread parks on between steps. Null until the thread is launched, which
     * is how [resume] tells a first step from a later one. Made then, so a hat that does not fire
     * allocates nothing but this object.
     */
    private var permit: Semaphore? = null

    @Volatile var done: Boolean = false
        private set

    /** Set to stop the script, which unwinds the next time it resumes. Tells the scheduler, which may have skipped it. */
    @Volatile var killed: Boolean = false
        set(value) {
            if (value && !field) runtime.threadsChanged()
            field = value
        }

    /** Set when the script yielded until the next frame (`wait`, `glide`) instead of a loop yield. */
    var tickYield: Boolean = false

    var warpDepth: Int = 0
    var warpStartNanos: Long = 0

    /** Loop yields left in warp mode before [yieldNow] looks at the clock and [killed] again. */
    private var warpChecksSkipped: Int = 0

    /** Whether the scheduler has picked the script once, so [start] has decided whether it runs. */
    var started: Boolean = false
        private set

    /**
     * Decides, the first time the scheduler picks the script, whether it runs at all. An edge-activated
     * hat checks its condition here, at the point in the frame where scratch-vm checks it, so a hat that
     * does not fire, which is most of them in most frames, never needs a thread. A condition that throws
     * counts as false, whatever it throws, since it runs on whichever thread is choosing the next script.
     */
    internal fun start(): Boolean {
        started = true
        val runs =
            try {
                !killed && (!edgeHat || target.edgeActivated(scriptId))
            } catch (t: Throwable) {
                Log.warn("The condition of script $scriptId in '${target.name}' failed with $t and counts as false")
                false
            }
        if (!runs) done = true
        return runs
    }

    /**
     * Gives control to this script. [Runtime.dispatch] calls it, on the scheduler thread for the first
     * script of a frame and on the yielding script's thread for every other one. The first time it
     * launches the virtual thread, which is a handoff by itself; after that the thread is parked in
     * [handOff] and gets its permit. Throws only when the virtual thread cannot be launched, and then
     * the dispatcher gives up on the script with [abandon].
     */
    internal fun resume() {
        val p = permit
        if (p != null) {
            p.release()
            return
        }
        // Set before the launch, which makes it visible to the new thread.
        permit = Semaphore(0)
        Thread.ofVirtual().name("script-${target.name}-$scriptId").start(::run)
    }

    /** Marks a script that could not be launched as finished, so the scheduler stops picking it. */
    internal fun abandon() {
        done = true
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
            handOff()
        }
    }

    /**
     * Hands control to whatever the scheduler picks next, or back to the scheduler thread when the frame
     * is over, and parks until this script is resumed. Returns at once when the scheduler picks this
     * script again, which is what happens when no other script is left to step in the frame.
     */
    private fun handOff() {
        if (runtime.dispatch(this)) return
        if (!done) permit!!.acquireUninterruptibly()
    }

    /** Called from inside the script: lets the other scripts run. */
    fun yieldNow(untilNextTick: Boolean) {
        if (warpDepth > 0) {
            // "Run without screen refresh": keep going, but never hog the frame for more than 500 ms.
            // Reading the clock and the volatile stop flag costs more than a cheap loop body, so only
            // every WARP_CHECK_INTERVAL-th yield does it.
            if (--warpChecksSkipped > 0) return
            warpChecksSkipped = WARP_CHECK_INTERVAL
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

    /** Enters "run without screen refresh", where loop yields return at once for up to 500 ms. */
    fun enterWarp() {
        if (warpDepth == 0) warpStartNanos = System.nanoTime()
        warpDepth++
    }

    /** Leaves one level of "run without screen refresh". */
    fun exitWarp() {
        if (warpDepth > 0) warpDepth--
    }

    companion object {
        private const val WARP_TIME_NANOS = 500_000_000L

        /**
         * How many loop yields in warp mode share one look at the clock. Small enough that a loop whose body
         * takes a millisecond, like stamping a large costume, still overshoots the 500 ms by a fraction of a
         * second at most.
         */
        private const val WARP_CHECK_INTERVAL = 256
    }
}
