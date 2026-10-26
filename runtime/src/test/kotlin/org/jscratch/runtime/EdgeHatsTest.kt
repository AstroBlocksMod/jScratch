package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** When edge-activated hats like `when timer greater than` start their script, frame by frame. */
class EdgeHatsTest {
    private class Backdrop(rt: Runtime) : Stage(rt, "Stage") {
        override fun runScript(id: Int) {}
    }

    /** A sprite with one edge-activated hat whose condition the test sets, and a script that can be held running. */
    private class Cat(rt: Runtime) : Sprite(rt, "Cat") {
        @Volatile var condition = false

        @Volatile var hold = false

        @Volatile var runs = 0

        init {
            registerHat(Runtime.HAT_EDGE, "", 0)
        }

        override fun hatPredicate(id: Int): Boolean = condition

        override fun runScript(id: Int) {
            runs++
            while (hold) rt.yieldTick()
        }

        override fun newInstance(): Target = Cat(rt)
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
    fun clearingTheConditionsFiresTheHatAgain() {
        cat.condition = true
        rt.frame()
        cat.clearEdgeValues()
        rt.frame()
        assertEquals(2, cat.runs)
    }
}
