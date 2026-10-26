package org.jscratch.runtime

import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * What a target's volume and sound effects do to its sounds, the way scratch-audio applies them.
 *
 * Pitch changes the playback rate, so it changes how long a sound lasts too: 10 is a semitone,
 * and the range of -360 to 360 covers three octaves either way. Volume is a plain gain. Pan mixes
 * the sound down to mono and spreads it over the two channels with equal loudness, while a pan of
 * 0 leaves the channels as they are, since scratch-audio takes the pan effect out of the chain then.
 *
 * The gains form a matrix from the sound's left and right channel to the output's.
 */
class SoundMix(volume: Double, pitch: Double, pan: Double) {
    val rate: Double = rate(pitch)
    val leftFromLeft: Double
    val leftFromRight: Double
    val rightFromLeft: Double
    val rightFromRight: Double

    init {
        val gain = volume / 100.0
        if (pan == 0.0) {
            leftFromLeft = gain
            leftFromRight = 0.0
            rightFromLeft = 0.0
            rightFromRight = gain
        } else {
            val angle = (pan + 100.0) / 200.0 * Math.PI / 2.0
            // Web Audio mixes stereo down to mono as half of each channel.
            leftFromLeft = gain * Math.cos(angle) / 2.0
            leftFromRight = leftFromLeft
            rightFromLeft = gain * Math.sin(angle) / 2.0
            rightFromRight = rightFromLeft
        }
    }

    companion object {
        fun of(target: Target): SoundMix = SoundMix(target.volume, target.pitch, target.pan)

        /** The playback rate for a pitch effect. */
        fun rate(pitch: Double): Double = Math.pow(2.0, pitch / 120.0)
    }
}

/**
 * Sound playback through javax.sound, with decoding by [SoundStream].
 *
 * [loadAll] runs when the project starts and opens every sound once to learn its length.
 * Short sounds are decoded into memory then, so they start at once. Long ones (music, mostly)
 * would take a lot of memory as PCM, so they are decoded while they play instead. Each playing
 * sound feeds its own stereo [SourceDataLine] from a daemon thread, which resamples it for the
 * pitch effect and applies volume and pan in software, so changes reach sounds already playing.
 *
 * Headless runs only read the lengths and play nothing, so "play sound until done" still
 * waits as long as the sound lasts.
 */
class Sounds(private val enabled: Boolean) {
    /** A sound that could be opened: its length, and its samples when it is short. */
    private class Loaded(val resource: String, val mp3: Boolean, val seconds: Double, val pcm: Pcm?)

    private class Playback(val target: Target, val line: SourceDataLine, val voice: Voice) {
        @Volatile var stopped = false
    }

    private val playing = ArrayList<Playback>()

    /** Loaded sounds by resource name, null for those that could not be opened. */
    private val loaded = HashMap<String, Loaded?>()

    /** How many times [stopAll] ran, so "play sound until done" can tell that its sound was stopped. */
    @Volatile var stops: Int = 0
        private set

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

    /**
     * Starts the sound with [target]'s volume and effects and returns its length in seconds at
     * normal pitch (0 when it could not be decoded).
     */
    fun play(sound: SoundAsset, target: Target): Double {
        val sound = load(sound) ?: return 0.0
        if (enabled) {
            val mix = SoundMix.of(target)
            Thread
                .ofPlatform()
                .daemon()
                .name("jscratch-sound")
                .start { feed(sound, target, mix) }
        }
        return sound.seconds
    }

    /** Applies [target]'s current volume and effects to the sounds it is playing. */
    fun update(target: Target) {
        if (!enabled) return
        val mix = SoundMix.of(target)
        synchronized(playing) {
            playing.forEach { if (it.target === target) it.voice.mix = mix }
        }
    }

    private fun feed(sound: Loaded, target: Target, mix: SoundMix) {
        var playback: Playback? = null
        var stream: SoundStream? = null
        try {
            val pcm = sound.pcm
            if (pcm == null) {
                val bytes = Assets.readResource("assets/" + sound.resource) ?: return
                stream = (if (sound.mp3) SoundStream.openMp3(bytes) else SoundStream.openWav(bytes)) ?: return
            }
            val voice = Voice(pcm, stream, mix)
            val format = audioFormat(voice.sampleRate, 2)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, voice.sampleRate / LINE_BUFFERS_PER_SECOND * 4)
            playback = Playback(target, line, voice)
            synchronized(playing) { playing.add(playback) }
            line.start()

            // Short chunks, so a new mix is heard within a chunk and the line's buffer.
            val out = ByteArray(CHUNK_FRAMES * 4)
            while (!playback.stopped) {
                val n = voice.read(out)
                if (n > 0) line.write(out, 0, n * 4)
                if (n < CHUNK_FRAMES) break
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

    /** Stops every playing sound. Stopping and flushing a line makes a blocked write return. */
    fun stopAll() {
        stops++
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

        /** The lines buffer a tenth of a second, so changes to the mix are heard soon. */
        private const val LINE_BUFFERS_PER_SECOND = 10
        private const val CHUNK_FRAMES = 1024
    }
}

/**
 * One playing sound, turned into stereo 16-bit samples with a [SoundMix]. Its frames come from
 * the cached [pcm] or are decoded from [stream] as they are needed. They are asked for in
 * increasing order, so a streamed sound only keeps the last chunk, plus the frame before it for
 * interpolation.
 *
 * Samples between two frames are interpolated linearly, as browsers do for a playback rate. The
 * gains glide to a new mix with a time constant of 25 ms, like the `setTargetAtTime` ramps in
 * scratch-audio, so changes do not click.
 */
internal class Voice(pcm: Pcm?, private val stream: SoundStream?, @Volatile var mix: SoundMix) {
    val channels: Int = pcm?.channels ?: stream!!.channels
    val sampleRate: Int = pcm?.sampleRate ?: stream!!.sampleRate
    private val frameBytes = channels * 2
    private val rightChannel = if (channels > 1) 1 else 0
    private val glide = 1.0 - Math.exp(-1.0 / (sampleRate * GLIDE_SECONDS))

    private var data: ByteArray = pcm?.data ?: ByteArray(0)
    private var first = 0L
    private var count = data.size / frameBytes
    private var position = 0.0
    private var ll = mix.leftFromLeft
    private var lr = mix.leftFromRight
    private var rl = mix.rightFromLeft
    private var rr = mix.rightFromRight

    /** Fills [out] with up to a quarter of its size in stereo frames. Fewer means the sound ended. */
    fun read(out: ByteArray): Int {
        val mix = mix
        val max = out.size / 4
        var n = 0
        while (n < max) {
            val frame = position.toLong()
            if (!has(frame)) break
            var l = sample(frame, 0).toDouble()
            var r = sample(frame, rightChannel).toDouble()
            val fraction = position - frame
            if (fraction > 0.0 && has(frame + 1)) {
                l += (sample(frame + 1, 0) - l) * fraction
                r += (sample(frame + 1, rightChannel) - r) * fraction
            }
            ll += (mix.leftFromLeft - ll) * glide
            lr += (mix.leftFromRight - lr) * glide
            rl += (mix.rightFromLeft - rl) * glide
            rr += (mix.rightFromRight - rr) * glide
            write(out, n * 4, l * ll + r * lr)
            write(out, n * 4 + 2, l * rl + r * rr)
            position += mix.rate
            n++
        }
        return n
    }

    /** Whether [frame] exists, decoding up to it if needed. */
    private fun has(frame: Long): Boolean {
        while (frame >= first + count) {
            if (stream == null) return false
            val chunk = stream.read(STREAM_FRAMES)
            if (chunk.isEmpty()) return false
            val kept = if (count > 0) frameBytes else 0
            val next = ByteArray(kept + chunk.size)
            data.copyInto(next, 0, data.size - kept, data.size)
            chunk.copyInto(next, kept)
            first += count - kept / frameBytes
            data = next
            count = next.size / frameBytes
        }
        return true
    }

    /** A sample of a frame that [has] returned true for, and that is not before the last one it did. */
    private fun sample(frame: Long, channel: Int): Int {
        val i = (frame - first).toInt() * frameBytes + channel * 2
        return (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
    }

    private fun write(out: ByteArray, offset: Int, value: Double) {
        val s = Math.round(value).coerceIn(-32768L, 32767L).toInt()
        out[offset] = s.toByte()
        out[offset + 1] = (s shr 8).toByte()
    }

    private companion object {
        const val STREAM_FRAMES = 4096
        const val GLIDE_SECONDS = 0.025
    }
}
