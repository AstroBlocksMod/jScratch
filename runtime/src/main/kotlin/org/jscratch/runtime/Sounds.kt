package org.jscratch.runtime

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent

/**
 * Sound playback through javax.sound. Sounds are decoded into PCM once, when the
 * project starts (see [SoundDecoders]). Headless runs decode too but play nothing,
 * so "play sound until done" still waits as long as the sound lasts.
 */
class Sounds(private val enabled: Boolean) {
    private val playing = ArrayList<Clip>()

    /** Decoded sounds by resource name, null for those that could not be decoded. */
    private val decoded = HashMap<String, Pcm?>()

    fun load(sound: SoundAsset): Pcm? {
        synchronized(decoded) {
            if (decoded.containsKey(sound.resource)) return decoded[sound.resource]
        }
        val pcm = decode(sound)
        synchronized(decoded) { decoded[sound.resource] = pcm }
        return pcm
    }

    private fun decode(sound: SoundAsset): Pcm? {
        val bytes = Assets.readResource("assets/" + sound.resource) ?: return null
        val name = sound.resource.lowercase()
        return try {
            val pcm =
                when {
                    name.endsWith(".mp3") -> SoundDecoders.decodeMp3(bytes)
                    name.endsWith(".wav") -> SoundDecoders.decodeWav(bytes)
                    else -> {
                        Log.warn("Sound '${sound.name}' (${sound.resource}) is neither MP3 nor WAV and will not play.")
                        return null
                    }
                }
            if (pcm == null) Log.warn("Could not decode sound '${sound.name}' (${sound.resource}).")
            pcm
        } catch (e: Exception) {
            Log.warn("Could not decode sound '${sound.name}' (${sound.resource}): $e")
            null
        }
    }

    /** Starts the sound and returns its duration in seconds (0 when it could not be decoded). */
    fun play(sound: SoundAsset, volume: Double): Double {
        val pcm = load(sound) ?: return 0.0
        if (!enabled) return pcm.seconds

        try {
            val clip = AudioSystem.getClip()
            clip.open(pcm.format, pcm.data, 0, pcm.data.size)
            applyVolume(clip, volume)
            synchronized(playing) {
                playing.removeAll { !it.isRunning && it.framePosition >= it.frameLength }
                playing.add(clip)
            }
            clip.addLineListener { e ->
                if (e.type == LineEvent.Type.STOP) {
                    clip.close()
                    synchronized(playing) { playing.remove(clip) }
                }
            }
            clip.start()
        } catch (e: Exception) {
            Log.warn("Could not play sound '${sound.name}': $e")
        }
        return pcm.seconds
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
