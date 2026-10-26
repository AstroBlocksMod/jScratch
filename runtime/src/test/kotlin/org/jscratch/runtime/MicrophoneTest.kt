package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class MicrophoneTest {
    @Test
    fun levelsFollowScratchAudio() {
        val mic = Microphone()
        assertEquals(0.0, mic.level(0.0))
        // 0.1 scaled by 1.63 is 0.163, whose square root is 0.404.
        assertEquals(40.0, mic.level(0.1))
        // Silence after that falls to 60% of the last value instead of dropping to 0.
        assertEquals(31.0, mic.level(0.0))
        assertEquals(24.0, mic.level(0.0))
        // Loud input is capped at 100.
        assertEquals(100.0, mic.level(1.0))
    }
}
