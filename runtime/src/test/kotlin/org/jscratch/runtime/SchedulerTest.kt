package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How scripts take turns within a frame and across frames, and how they stop. */
class SchedulerTest {
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A sprite whose scripts are Kotlin lambdas, started by the green flag or by a broadcast. */
    private class Bot(rt: Runtime) : Sprite(rt, "Bot") {
        private val scripts = ArrayList<() -> Unit>()

        fun onFlag(body: () -> Unit) {
            registerHat(Runtime.HAT_FLAG, "", scripts.size)
            scripts.add(body)
        }

        fun onBroadcast(name: String, body: () -> Unit) {
            registerHat(Runtime.HAT_BROADCAST, name, scripts.size)
            scripts.add(body)
        }

        override fun runScript(id: Int) = scripts[id]()

        override fun newInstance(): Target = Bot(rt)
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

    private fun flag() {
        rt.startHats(Runtime.HAT_FLAG, "", null)
    }

    @Test
    fun loopScriptsTakeTurnsWithinAFrame() {
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
        rt.frame()
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), log)
        assertTrue(rt.threads.isEmpty())
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
            repeat(2) {
                log.add("l")
                rt.yieldLoop()
            }
        }
        flag()
        rt.frame()
        assertEquals(listOf("w", "l", "l"), log, "the loop gets the rest of the frame")
        rt.frame()
        assertEquals(listOf("w", "l", "l", "w"), log)
        rt.frame()
        rt.frame()
        assertEquals(listOf("w", "l", "l", "w", "w"), log)
        assertTrue(rt.threads.isEmpty())
    }

    @Test
    fun aLoneLoopRunsOnWithoutSwitchingThreads() {
        var iterations = 0
        bot.onFlag {
            repeat(10000) {
                iterations++
                rt.yieldLoop()
            }
        }
        flag()
        rt.frame()
        assertEquals(10000, iterations, "finishes within the first frame")
        assertTrue(rt.threads.isEmpty())
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
    fun aBroadcastStartsScriptsInTheSameFrame() {
        bot.onFlag {
            log.add("flag")
            rt.broadcast("go")
            rt.yieldLoop()
            log.add("flag again")
        }
        bot.onBroadcast("go") { log.add("go") }
        flag()
        rt.frame()
        assertEquals(listOf("flag", "go", "flag again"), log)
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
}
