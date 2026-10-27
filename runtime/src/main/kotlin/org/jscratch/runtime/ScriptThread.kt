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

    /**
     * Set to stop the script, which unwinds the next time it resumes or yields. Tells the scheduler, which may
     * have skipped it, and makes the next loop yield in warp mode look at the flag. A script that restarts
     * itself with a broadcast sets the flag on its own thread, so it always stops at that yield. From another
     * thread, like the stop button, the write to the countdown can race, and the stop comes one run of
     * yields later at most.
     */
    @Volatile var killed: Boolean = false
        set(value) {
            if (value && !field) {
                warpChecksSkipped = 0
                runtime.threadsChanged()
            }
            field = value
        }

    /** Set when the script yielded until the next frame (`wait`, `glide`) instead of a loop yield. */
    var tickYield: Boolean = false

    var warpDepth: Int = 0
    var warpStartNanos: Long = 0

    /** Loop yields left in warp mode before [yieldNow] looks at the clock and [killed] again. */
    private var warpChecksSkipped: Int = 0

    /** How many loop yields in warp mode share one look at the clock, see [yieldNow]. */
    private var warpCheckInterval: Int = 1

    /** When [yieldNow] last looked at the clock in warp mode. */
    private var warpLastCheckNanos: Long = 0

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
            // Reading the clock and the volatile stop flag costs more than a cheap loop body, so a run of
            // yields shares one look. The run doubles while looks stay under WARP_CHECK_NANOS apart and
            // drops back to one yield when they do not, so a loop with a slow body checks every time.
            // A stop is noticed at the next look, much like scratch-vm, where a warp loop runs on until
            // it actually yields.
            if (--warpChecksSkipped > 0) return
            val now = System.nanoTime()
            warpCheckInterval =
                if (now - warpLastCheckNanos < WARP_CHECK_NANOS) {
                    Math.min(warpCheckInterval * 2, MAX_WARP_CHECK_INTERVAL)
                } else {
                    1
                }
            warpChecksSkipped = warpCheckInterval
            warpLastCheckNanos = now
            if (now - warpStartNanos < WARP_TIME_NANOS) {
                if (killed) throw StopScript()
                return
            }
            tickYield = true
            handOff()
            restartWarpTimer()
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
        if (warpDepth > 0) restartWarpTimer()
        if (killed) throw StopScript()
    }

    /** Enters "run without screen refresh", where loop yields return at once for up to 500 ms. */
    fun enterWarp() {
        if (warpDepth == 0) restartWarpTimer()
        warpDepth++
    }

    /** Starts the 500 ms over, with the first loop yield after it looking at the clock again. */
    private fun restartWarpTimer() {
        warpStartNanos = System.nanoTime()
        warpLastCheckNanos = warpStartNanos
        warpCheckInterval = 1
        warpChecksSkipped = 0
    }

    /** Leaves one level of "run without screen refresh". */
    fun exitWarp() {
        if (warpDepth > 0) warpDepth--
    }

    companion object {
        private const val WARP_TIME_NANOS = 500_000_000L

        /** Looks at the clock in warp mode closer together than this let the next run of yields grow. */
        private const val WARP_CHECK_NANOS = 1_000_000L

        /**
         * The longest run of loop yields that shares one look at the clock. A run only gets this long when
         * the last one took under a millisecond, and checking more often than that measured no faster.
         */
        private const val MAX_WARP_CHECK_INTERVAL = 256
    }
}
