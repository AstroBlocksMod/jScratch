package org.jscratch.runtime

import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

/**
 * Sound playback through javax.sound, with decoding by [SoundStream].
 *
 * [loadAll] runs when the project starts and opens every sound once to learn its length.
 * Short sounds are decoded into memory then, so they start at once. Long ones (music, mostly)
 * would take a lot of memory as PCM, so they are decoded while they play instead. Each playing
 * sound feeds its own [SourceDataLine] from a daemon thread.
 *
 * Headless runs only read the lengths and play nothing, so "play sound until done" still
 * waits as long as the sound lasts.
 */
class Sounds(private val enabled: Boolean) {
    /** A sound that could be opened: its length, and its samples when it is short. */
    private class Loaded(val resource: String, val mp3: Boolean, val seconds: Double, val pcm: Pcm?)

    private class Playback(val line: SourceDataLine) {
        @Volatile var stopped = false
    }

    private val playing = ArrayList<Playback>()

    /** Loaded sounds by resource name, null for those that could not be opened. */
    private val loaded = HashMap<String, Loaded?>()

    /** Opens every sound in parallel. Decoding is CPU work, so virtual threads spread it over the cores. */
    fun loadAll(sounds: Collection<SoundAsset>) {
        val unique = sounds.distinctBy { it.resource }
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            unique.forEach { sound -> pool.submit { load(sound) } }
        }
    }

    private fun load(sound: SoundAsset): Loaded? {
        synchronized(loaded) {
            if (loaded.containsKey(sound.resource)) return loaded[sound.resource]
        }
        val result = open(sound)
        synchronized(loaded) { loaded[sound.resource] = result }
        return result
    }

    private fun open(sound: SoundAsset): Loaded? {
        val name = sound.resource.lowercase()
        val mp3 =
            when {
                name.endsWith(".mp3") -> true
                name.endsWith(".wav") -> false
                else -> {
                    Log.warn("Sound '${sound.name}' (${sound.resource}) is neither MP3 nor WAV and will not play.")
                    return null
                }
            }
        val bytes = Assets.readResource("assets/" + sound.resource) ?: return null
        return try {
            val stream = (if (mp3) SoundStream.openMp3(bytes) else SoundStream.openWav(bytes))
            if (stream == null) {
                Log.warn("Could not decode sound '${sound.name}' (${sound.resource}).")
                return null
            }
            val seconds = stream.use { it.seconds }
            val pcm =
                if (enabled && seconds <= MAX_CACHED_SECONDS) {
                    if (mp3) SoundDecoders.decodeMp3(bytes) else SoundDecoders.decodeWav(bytes)
                } else {
                    null
                }
            Loaded(sound.resource, mp3, pcm?.seconds ?: seconds, pcm)
        } catch (e: Exception) {
            Log.warn("Could not decode sound '${sound.name}' (${sound.resource}): $e")
            null
        }
    }

    /** Starts the sound and returns its duration in seconds (0 when it could not be decoded). */
    fun play(sound: SoundAsset, volume: Double): Double {
        val sound = load(sound) ?: return 0.0
        if (enabled) {
            Thread
                .ofPlatform()
                .daemon()
                .name("jscratch-sound")
                .start { feed(sound, volume) }
        }
        return sound.seconds
    }

    private fun feed(sound: Loaded, volume: Double) {
        var playback: Playback? = null
        var stream: SoundStream? = null
        try {
            val pcm = sound.pcm
            if (pcm == null) {
                val bytes = Assets.readResource("assets/" + sound.resource) ?: return
                stream = (if (sound.mp3) SoundStream.openMp3(bytes) else SoundStream.openWav(bytes)) ?: return
            }
            val format = pcm?.format ?: stream!!.format
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format)
            applyVolume(line, volume)
            playback = Playback(line)
            synchronized(playing) { playing.add(playback) }
            line.start()

            if (pcm != null) {
                var offset = 0
                while (offset < pcm.data.size && !playback.stopped) {
                    val n = minOf(WRITE_BYTES, pcm.data.size - offset)
                    offset += line.write(pcm.data, offset, n)
                }
            } else {
                while (!playback.stopped) {
                    val chunk = stream!!.read(STREAM_FRAMES)
                    if (chunk.isEmpty()) break
                    line.write(chunk, 0, chunk.size)
                }
            }
            if (!playback.stopped) line.drain()
        } catch (e: Exception) {
            Log.warn("Could not play sound ${sound.resource}: $e")
        } finally {
            stream?.close()
            if (playback != null) {
                synchronized(playing) { playing.remove(playback) }
                playback.line.close()
            }
        }
    }

    private fun applyVolume(line: SourceDataLine, volume: Double) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val v = (volume / 100.0).coerceIn(0.0001, 1.0)
        gain.value = (20.0 * Math.log10(v)).toFloat().coerceIn(gain.minimum, gain.maximum)
    }

    /** Stops every playing sound. Stopping and flushing a line makes a blocked write return. */
    fun stopAll() {
        synchronized(playing) {
            playing.forEach {
                it.stopped = true
                try {
                    it.line.stop()
                    it.line.flush()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        /** Sounds up to this long are kept decoded. Ten seconds of 44.1 kHz stereo is about 1.7 MB. */
        const val MAX_CACHED_SECONDS = 10.0

        private const val WRITE_BYTES = 16384
        private const val STREAM_FRAMES = 4096
    }
}
