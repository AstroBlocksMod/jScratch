package org.jscratch.runtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How scripts take turns within a frame and across frames, and how they stop.
 *
 * A frame also ends once 75 percent of its time is used, which a slow machine can reach at any pass. Passes
 * are never cut short, though, so the tests check the order things happen in across frames, not what fits
 * into one frame, unless a redraw or a wait decides where the frame ends.
 */
class SchedulerTest {
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A sprite whose scripts are Kotlin lambdas, started by the green flag, a broadcast or a condition. */
    private class Bot(rt: Runtime, name: String = "Bot") : Sprite(rt, name) {
        private val scripts = ArrayList<() -> Unit>()
        private val conditions = HashMap<Int, () -> Boolean>()

        fun onFlag(body: () -> Unit) {
            registerHat(Runtime.HAT_FLAG, "", scripts.size)
            scripts.add(body)
        }

        fun onBroadcast(name: String, body: () -> Unit) {
            registerHat(Runtime.HAT_BROADCAST, name, scripts.size)
            scripts.add(body)
        }

        /** An edge-activated hat. Register it before the sprite is added, which is when the runtime looks for them. */
        fun onCondition(condition: () -> Boolean, body: () -> Unit) {
            registerHat(Runtime.HAT_EDGE, "touching", scripts.size)
            conditions[scripts.size] = condition
            scripts.add(body)
        }

        override fun hatPredicate(id: Int): Boolean = conditions.getValue(id)()

        override fun runScript(id: Int) = scripts[id]()

        override fun newInstance(): Target = Bot(rt, name)
    }

    private val rt: Runtime

    init {
        System.setProperty("jscratch.headless", "0")
        rt = Runtime()
    }

    private val bot = Bot(rt)

    /** Written by the scripts, read by the test between frames. */
    private val log = ArrayList<String>()

    init {
        rt.addTarget(Backdrop(rt))
        rt.addTarget(bot)
    }

    /** Unwinds the scripts a test left parked, so their virtual threads end with the test. */
    @AfterTest
    fun stopEverything() {
        rt.stopAll()
        var frames = 0
        while (rt.threads.isNotEmpty() && frames++ < 10) rt.frame()
    }

    private fun flag() {
        rt.startHats(Runtime.HAT_FLAG, "", null)
    }

    /** Runs frames until no script is left. */
    private fun runUntilDone() {
        var frames = 0
        while (rt.threads.isNotEmpty()) {
            check(frames++ < 1000) { "the scripts never finished" }
            rt.frame()
        }
    }

    @Test
    fun loopScriptsTakeTurns() {
        bot.onFlag {
            repeat(3) {
                log.add("a")
                rt.yieldLoop()
            }
        }
        bot.onFlag {
            repeat(3) {
                log.add("b")
                rt.yieldLoop()
            }
        }
        flag()
        runUntilDone()
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), log)
    }

    @Test
    fun aScriptThatWaitsRunsOncePerFrame() {
        bot.onFlag {
            repeat(3) {
                log.add("w")
                rt.yieldTick()
            }
        }
        bot.onFlag {
            repeat(5) {
                log.add("l")
                rt.yieldLoop()
            }
        }
        flag()
        for (frame in 1..3) {
            rt.frame()
            assertEquals(frame, log.count { it == "w" })
        }
        runUntilDone()
        assertEquals(3, log.count { it == "w" })
        assertEquals(5, log.count { it == "l" })
    }

    @Test
    fun aBusyLoopYieldsWithoutThreadSwitchesWhileOthersWait() {
        repeat(5) {
            bot.onFlag {
                while (true) rt.yieldTick()
            }
        }
        var iterations = 0
        bot.onFlag {
            repeat(10000) {
                iterations++
                rt.yieldLoop()
            }
        }
        flag()
        var frames = 0
        while (iterations < 10000) {
            check(frames++ < 1000) { "the loop never finished" }
            val before = rt.handoffs
            rt.frame()
            // Each script gets control once, in the first pass, and the loop keeps it after that.
            assertEquals(6, rt.handoffs - before, "handoffs in frame $frames")
        }
    }

    @Test
    fun aRedrawEndsTheFrame() {
        bot.onFlag {
            repeat(3) {
                log.add("r")
                rt.requestRedraw()
                rt.yieldLoop()
            }
        }
        flag()
        rt.frame()
        assertEquals(listOf("r"), log)
        rt.frame()
        assertEquals(listOf("r", "r"), log)
    }

    @Test
    fun warpLoopsDoNotYield() {
        bot.onFlag {
            bot.enterWarp()
            repeat(3) {
                log.add("w")
                rt.yieldLoop()
            }
            bot.exitWarp()
        }
        bot.onFlag { log.add("b") }
        flag()
        rt.frame()
        assertEquals(listOf("w", "w", "w", "b"), log)
    }

    @Test
    fun aWarpLoopGivesUpTheFrameAfter500Ms() {
        bot.onFlag {
            bot.enterWarp()
            val start = System.nanoTime()
            while (System.nanoTime() - start < 700_000_000L) rt.yieldLoop()
            log.add("w")
            bot.exitWarp()
        }
        bot.onFlag { log.add("b") }
        flag()
        runUntilDone()
        assertEquals(listOf("b", "w"), log)
    }

    @Test
    fun aBroadcastStartsScriptsInTheSamePass() {
        bot.onFlag {
            log.add("flag")
            rt.broadcast("go")
            rt.yieldLoop()
            log.add("flag again")
        }
        bot.onBroadcast("go") { log.add("go") }
        flag()
        rt.frame()
        assertEquals(listOf("flag", "go"), log.take(2))
        runUntilDone()
        assertEquals(listOf("flag", "go", "flag again"), log)
    }

    @Test
    fun aLoneLoopThatBroadcastsLetsTheNewScriptRunNext() {
        bot.onFlag {
            log.add("w")
            while (true) rt.yieldTick()
        }
        bot.onFlag {
            for (i in 0 until 6) {
                if (i == 3) rt.broadcast("go")
                log.add("$i")
                rt.yieldLoop()
            }
        }
        bot.onBroadcast("go") { log.add("go") }
        flag()
        var frames = 0
        while (log.lastOrNull() != "5") {
            check(frames++ < 1000) { "the loop never finished" }
            rt.frame()
        }
        assertEquals(listOf("w", "0", "1", "2", "3", "go", "4", "5"), log)
    }

    @Test
    fun aLoneLoopThatStopsAWaitingScriptUnwindsItAtOnce() {
        bot.onFlag {
            log.add("w")
            try {
                while (true) rt.yieldTick()
            } finally {
                log.add("w stopped")
            }
        }
        bot.onFlag {
            for (i in 0 until 6) {
                if (i == 3) rt.stopOtherScripts(bot)
                log.add("$i")
                rt.yieldLoop()
            }
        }
        flag()
        runUntilDone()
        assertEquals(listOf("w", "0", "1", "2", "3", "w stopped", "4", "5"), log)
    }

    @Test
    fun stopAllUnwindsEveryScript() {
        bot.onFlag {
            while (true) {
                log.add("a")
                rt.yieldLoop()
            }
        }
        bot.onFlag {
            log.add("stop")
            rt.stopAll()
            log.add("not reached")
        }
        flag()
        rt.frame()
        assertEquals(listOf("a", "stop"), log)
        // Stopping clears the say bubbles, which requests a redraw and ends the frame, so the killed loop
        // unwinds when the next frame steps it.
        rt.frame()
        assertEquals(listOf("a", "stop"), log)
        assertTrue(rt.threads.isEmpty())
    }

    @Test
    fun restartingAScriptKillsTheOldRun() {
        bot.onFlag {
            log.add("start")
            rt.yieldTick()
            log.add("end")
        }
        flag()
        rt.frame()
        flag()
        rt.frame()
        assertEquals(listOf("start", "start"), log)
        rt.frame()
        assertEquals(listOf("start", "start", "end"), log)
        assertTrue(rt.threads.isEmpty())
    }

    @Test
    fun aHatConditionThatThrowsAnErrorDoesNotStopTheYieldingScript() {
        val broken = Bot(rt, "Broken")
        broken.onCondition({ throw StackOverflowError() }) { log.add("never") }
        rt.addTarget(broken)
        bot.onFlag {
            repeat(3) {
                log.add("a")
                rt.yieldLoop()
            }
        }
        flag()
        repeat(5) { rt.frame() }
        assertEquals(listOf("a", "a", "a"), log)
    }
}
