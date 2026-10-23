package org.jscratch.runtime

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl

/**
 * Minimal sound playback through javax.sound. WAV works; MP3 has no decoder in
 * the JDK and is skipped with a warning.
 */
class Sounds(private val enabled: Boolean) {
    private val playing = ArrayList<Clip>()

    /** Starts the sound and returns its duration in seconds (0 when it could not be played). */
    fun play(sound: SoundAsset, volume: Double): Double {
        if (!enabled) return 0.0
        if (!sound.resource.endsWith(".wav", ignoreCase = true)) {
            Log.warn("Sound '${sound.name}' (${sound.resource}) is not a WAV file; MP3 playback is not supported yet.")
            return 0.0
        }

        val bytes = Assets.readResource("assets/" + sound.resource) ?: return 0.0
        return try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val clip = AudioSystem.getClip()
            clip.open(stream)
            applyVolume(clip, volume)
            synchronized(playing) {
                playing.removeAll { !it.isRunning && it.framePosition >= it.frameLength }
                playing.add(clip)
            }
            clip.addLineListener { e ->
                if (e.type == javax.sound.sampled.LineEvent.Type.STOP) {
                    clip.close()
                    synchronized(playing) { playing.remove(clip) }
                }
            }
            clip.start()
            clip.microsecondLength / 1_000_000.0
        } catch (e: Exception) {
            Log.warn("Could not play sound '${sound.name}': $e")
            0.0
        }
    }

    private fun applyVolume(clip: Clip, volume: Double) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val v = (volume / 100.0).coerceIn(0.0001, 1.0)
        gain.value = (20.0 * Math.log10(v)).toFloat().coerceIn(gain.minimum, gain.maximum)
    }

    fun stopAll() {
        synchronized(playing) {
            playing.forEach {
                try {
                    it.stop()
                    it.close()
                } catch (_: Exception) {
                }
            }
            playing.clear()
        }
    }
}
