package org.jscratch.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceTest {
    private val normal = SoundMix(100.0, 0.0, 0.0)

    private fun pcm(channels: Int, vararg samples: Int): Pcm {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            data[2 * i] = s.toByte()
            data[2 * i + 1] = (s shr 8).toByte()
        }
        return Pcm(channels, 44100, samples.size / channels, data)
    }

    /** Every stereo frame the voice produces, as left and right sample pairs flattened. */
    private fun render(voice: Voice, chunkFrames: Int = 1024): List<Int> {
        val out = ByteArray(chunkFrames * 4)
        val samples = ArrayList<Int>()
        while (true) {
            val n = voice.read(out)
            for (i in 0 until n * 2) samples.add((out[2 * i].toInt() and 0xff) or (out[2 * i + 1].toInt() shl 8))
            if (n < chunkFrames) return samples
        }
    }

    @Test
    fun playsMonoOnBothChannels() {
        assertEquals(
            listOf(1000, 1000, -2000, -2000, 3000, 3000),
            render(Voice(pcm(1, 1000, -2000, 3000), null, normal)),
        )
    }

    @Test
    fun keepsStereoChannelsApartWithoutPan() {
        assertEquals(listOf(1000, -1000, 2000, -2000), render(Voice(pcm(2, 1000, -1000, 2000, -2000), null, normal)))
    }

    @Test
    fun scalesByVolume() {
        assertEquals(listOf(500, 500, -1000, -1000), render(Voice(pcm(1, 1000, -2000), null, SoundMix(50.0, 0.0, 0.0))))
    }

    @Test
    fun octaveUpSkipsEveryOtherFrame() {
        val voice = Voice(pcm(1, 0, 100, 200, 300, 400, 500, 600, 700), null, SoundMix(100.0, 120.0, 0.0))
        assertEquals(listOf(0, 0, 200, 200, 400, 400, 600, 600), render(voice))
    }

    @Test
    fun octaveDownInterpolates() {
        val voice = Voice(pcm(1, 0, 1000), null, SoundMix(100.0, -120.0, 0.0))
        // The last frame has nothing after it to interpolate with, so it repeats.
        assertEquals(listOf(0, 0, 500, 500, 1000, 1000, 1000, 1000), render(voice))
    }

    @Test
    fun panMixesDownToMonoWithEqualLoudness() {
        val stereo = pcm(2, 1000, 3000)
        assertEquals(listOf(0, 2000), render(Voice(stereo, null, SoundMix(100.0, 0.0, 100.0))))
        assertEquals(listOf(2000, 0), render(Voice(stereo, null, SoundMix(100.0, 0.0, -100.0))))
        // Halfway to the right: cos and sin of 3/8 of a half turn.
        assertEquals(listOf(765, 1848), render(Voice(stereo, null, SoundMix(100.0, 0.0, 50.0))))
    }

    @Test
    fun glidesToANewMix() {
        val voice = Voice(pcm(1, *IntArray(44100) { 10000 }), null, normal)
        val out = ByteArray(4)
        voice.read(out)
        voice.mix = SoundMix(0.0, 0.0, 0.0)
        val after = render(voice).filterIndexed { i, _ -> i % 2 == 0 }
        // No click: the first frame barely changes, and the sound fades out within a quarter second.
        assertTrue(after[0] > 9900, "first sample after the change is ${after[0]}")
        assertTrue(after.zipWithNext().all { (a, b) -> b <= a }, "volume does not only go down")
        assertEquals(0, after[11025], "still audible 250 ms after the change")
    }

    @Test
    fun fadesOutWhenStopped() {
        val voice = Voice(pcm(1, *IntArray(44100) { 10000 }), null, normal)
        voice.read(ByteArray(4))
        voice.fadeOut()
        val after = render(voice).filterIndexed { i, _ -> i % 2 == 0 }
        // 25 ms at 44.1 kHz, falling in a straight line from where the sound was.
        assertEquals(1102, after.size)
        assertEquals(10000, after[0])
        assertEquals(5000, after[551])
        assertTrue(after.last() < 10)
    }

    /** A streamed sound has to come out exactly like the cached one, across the decoder's chunks too. */
    @Test
    fun streamsLikeCachedSamples() {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/assets/beep.mp3")).use { it.readBytes() }
        val cached = assertNotNull(SoundDecoders.decodeMp3(bytes))
        for (mix in listOf(normal, SoundMix(70.0, 130.0, -30.0), SoundMix(100.0, -75.0, 0.0))) {
            val stream = assertNotNull(SoundStream.openMp3(bytes))
            val streamed = stream.use { render(Voice(null, it, mix), chunkFrames = 1000) }
            val expected = render(Voice(cached, null, mix), chunkFrames = 1000)
            assertTrue(expected.size > 1000)
            assertContentEquals(expected, streamed)
        }
    }
}
