package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which plays of a sound stop when a target stops its other scripts or a clone is deleted. Headless,
 * so nothing is played, and the clock stands still, so every sound keeps playing until it is stopped.
 */
class SoundsTest {
    private class Cat(rt: Runtime) : Sprite(rt, "Cat") {
        init {
            addSound("beep", "beep.mp3")
        }

        override fun runScript(id: Int) {}

        override fun newInstance(): Target = Cat(rt)
    }

    private val rt: Runtime

    init {
        System.setProperty("jscratch.headless", "0")
        rt = Runtime()
    }

    private val cat = Cat(rt)

    /** Plays the beep from [target] and returns a check for whether a wait begun now is over. */
    private fun play(target: Target, untilDone: Boolean): () -> Boolean {
        val player = assertNotNull(rt.sounds.play(target, 0, untilDone))
        val stops = player.stops
        return { player.isFinished(stops, rt.nowMillis()) }
    }

    @Test
    fun stopOtherScriptsStopsSoundsPlayedUntilDone() {
        val finished = play(cat, untilDone = true)
        assertFalse(finished())
        rt.stopOtherScripts(cat)
        assertTrue(finished())
    }

    @Test
    fun stopOtherScriptsLeavesSoundsPlayedWithoutWaiting() {
        val finished = play(cat, untilDone = false)
        rt.stopOtherScripts(cat)
        assertFalse(finished())
    }

    @Test
    fun soundTakenOverByACloneIsTheClones() {
        val clone = assertNotNull(rt.createClone(cat))
        play(cat, untilDone = true)
        val finished = play(clone, untilDone = true)
        rt.stopOtherScripts(cat)
        assertFalse(finished(), "stopping the parent stopped the clone's sound")
        rt.removeClone(clone)
        assertTrue(finished(), "deleting the clone left its sound playing")
    }

    @Test
    fun deletedCloneIsForgottenButItsSoundPlaysOn() {
        val clone = assertNotNull(rt.createClone(cat))
        val player = assertNotNull(rt.sounds.play(clone, 0, false))
        val stops = player.stops
        rt.removeClone(clone)
        assertNull(player.target)
        assertFalse(player.isFinished(stops, rt.nowMillis()))
    }

    @Test
    fun notANumberCountsAsZero() {
        cat.setSoundEffect("pitch", 50.0)
        cat.changeSoundEffect("PITCH", Double.NaN)
        assertEquals(50.0, cat.pitch)
        cat.setSoundEffect("pan", Double.NaN)
        assertEquals(0.0, cat.pan)
        cat.changeVolume(Double.NaN)
        assertEquals(100.0, cat.volume)
        cat.setVolume(Double.NaN)
        assertEquals(0.0, cat.volume)
    }
}
