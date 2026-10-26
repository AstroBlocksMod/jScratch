package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** When edge-activated hats like `when timer greater than` start their script, frame by frame. */
class EdgeHatsTest {
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A sprite with one edge-activated hat whose condition the test sets, and a script that can be held running. */
    private class Cat(rt: Runtime, private val watches: String = "timer") : Sprite(rt, "Cat") {
        @Volatile var condition = false

        @Volatile var hold = false

        @Volatile var runs = 0

        init {
            registerHat(Runtime.HAT_EDGE, watches, 0)
        }

        override fun hatPredicate(id: Int): Boolean = condition

        override fun runScript(id: Int) {
            runs++
            while (hold) rt.yieldTick()
        }

        override fun newInstance(): Target = Cat(rt, watches)
    }

    private val rt: Runtime

    init {
        System.setProperty("jscratch.headless", "0")
        rt = Runtime()
    }

    private val cat = Cat(rt)

    init {
        rt.addTarget(Backdrop(rt))
        rt.addTarget(cat)
    }

    @Test
    fun firesWhenTheConditionTurnsTrue() {
        rt.frame()
        assertEquals(0, cat.runs)
        cat.condition = true
        rt.frame()
        assertEquals(1, cat.runs)
        rt.frame()
        assertEquals(1, cat.runs, "still true, so it does not fire again")
        cat.condition = false
        rt.frame()
        cat.condition = true
        rt.frame()
        assertEquals(2, cat.runs)
    }

    @Test
    fun aConditionThatIsTrueFromTheStartFiresOnce() {
        cat.condition = true
        rt.frame()
        rt.frame()
        assertEquals(1, cat.runs)
    }

    @Test
    fun theConditionIsNotCheckedWhileTheScriptRuns() {
        cat.condition = true
        cat.hold = true
        rt.frame()
        assertEquals(1, cat.runs)
        // False and true again while the script runs: nothing checks it, so it never turned true.
        cat.condition = false
        rt.frame()
        cat.condition = true
        rt.frame()
        cat.hold = false
        rt.frame()
        rt.frame()
        assertEquals(1, cat.runs)
    }

    @Test
    fun clonesStartWithTheirParentsConditions() {
        cat.condition = true
        rt.frame()
        val clone = assertNotNull(rt.createClone(cat)) as Cat
        clone.condition = true
        rt.frame()
        assertEquals(0, clone.runs)
    }

    @Test
    fun headlessRunsWaitOnlyForHatsThatCanStillFire() {
        val toucher = Cat(rt, "touching")
        rt.addTarget(toucher)
        assertTrue(cat.edgeHatWaiting(), "never checked")
        assertTrue(toucher.edgeHatWaiting(), "never checked")
        rt.frame()
        assertTrue(cat.edgeHatWaiting(), "the timer keeps going, so a false timer hat can still fire")
        assertFalse(toucher.edgeHatWaiting(), "nothing moves without scripts, so a false touching hat stays false")
        cat.condition = true
        rt.frame()
        assertFalse(cat.edgeHatWaiting())
    }

    @Test
    fun clearingTheConditionsFiresTheHatAgain() {
        cat.condition = true
        rt.frame()
        cat.clearEdgeValues()
        rt.frame()
        assertEquals(2, cat.runs)
    }
}
