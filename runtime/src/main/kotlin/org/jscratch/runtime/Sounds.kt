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
 * Like scratch-audio's sound bank, there is one [SoundPlayer] for each sound of a sprite, shared
 * with its clones, so playing a sound that is still playing starts it over. Whether a sound is
 * still playing is counted on the runtime clock, so headless runs, which play nothing, behave the
 * same.
 */
class Sounds(private val rt: Runtime) {
    /** A sound that could be opened: its length, and its samples when it is short. */
    private class Loaded(val resource: String, val mp3: Boolean, val seconds: Double, val pcm: Pcm?)

    private val enabled = !rt.headless

    /** Players by the target's generated class, which its clones share, and the sound's index. */
    private val players = HashMap<Pair<Class<*>, Int>, SoundPlayer>()

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

    /**
     * Plays sound number [index] of [target] with its volume and effects, and returns its player,
     * or null when the sound could not be decoded.
     *
     * A sound that is still playing starts over, which ends the waits for it, the way
     * scratch-audio's `SoundBank.playSound` does. The exception is a sound the same target started
     * in this same frame: scratch-audio leaves a sound alone in its first 25 ms, and the audio
     * clock never moves while scratch-vm runs a frame's scripts, so it only ends the waits.
     */
    fun play(target: Target, index: Int): SoundPlayer? {
        val sound = load(target.sounds[index]) ?: return null
        val player = players.getOrPut(Pair(target.javaClass, index)) { SoundPlayer() }
        val now = rt.nowMillis()
        if (player.isPlaying(now)) {
            if (player.target === target && player.startFrame == rt.frame) {
                player.stops++
                return player
            }
            player.stop()
        }
        val mix = SoundMix.of(target)
        val audio = if (enabled) Audio(mix) else null
        player.start(target, sound.seconds * 1000.0, now, rt.frame, mix.rate, audio)
        if (audio != null) {
            Thread
                .ofPlatform()
                .daemon()
                .name("jscratch-sound")
                .start { feed(sound, audio) }
        }
        return player
    }

    /** Applies [target]'s current volume and effects to the sounds it is playing. */
    fun update(target: Target) {
        val mix = SoundMix.of(target)
        val now = rt.nowMillis()
        for (player in players.values) {
            if (player.target === target) player.remix(mix, now)
        }
    }

    /** Stops every playing sound. */
    fun stopAll() {
        for (player in players.values) player.stop()
    }

    private fun feed(sound: Loaded, audio: Audio) {
        var stream: SoundStream? = null
        var line: SourceDataLine? = null
        try {
            val pcm = sound.pcm
            if (pcm == null) {
                val bytes = Assets.readResource("assets/" + sound.resource) ?: return
                stream = (if (sound.mp3) SoundStream.openMp3(bytes) else SoundStream.openWav(bytes)) ?: return
            }
            val voice = Voice(pcm, stream, audio.mix)
            if (!audio.attach(voice)) return
            val format = audioFormat(voice.sampleRate, 2)
            line = AudioSystem.getSourceDataLine(format)
            line.open(format, voice.sampleRate / LINE_BUFFERS_PER_SECOND * 4)
            line.start()

            // Short chunks, so a new mix is heard within a chunk and the line's buffer.
            val out = ByteArray(CHUNK_FRAMES * 4)
            while (true) {
                val n = voice.read(out)
                if (n > 0) line.write(out, 0, n * 4)
                if (n < CHUNK_FRAMES) break
            }
            line.drain()
        } catch (e: Exception) {
            Log.warn("Could not play sound ${sound.resource}: $e")
        } finally {
            stream?.close()
            line?.close()
        }
    }

    /**
     * The audio of one play of a sound, shared between the player and the thread that feeds it.
     * The thread attaches its [Voice] once the sound is open, and a mix or stop that came before
     * then still reaches it.
     */
    internal class Audio(mix: SoundMix) {
        @Volatile var mix: SoundMix = mix
            private set

        @Volatile private var voice: Voice? = null

        @Volatile private var stopped = false

        /** Returns false when the sound was stopped before it could start. */
        fun attach(v: Voice): Boolean {
            voice = v
            v.mix = mix
            return !stopped
        }

        fun remix(m: SoundMix) {
            mix = m
            voice?.mix = m
        }

        /** Fades the sound out over 25 ms after what the line already holds, like scratch-audio's stop. */
        fun stop() {
            stopped = true
            voice?.fadeOut()
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
 * One sound of a sprite and its clones, like a `SoundPlayer` in scratch-audio: at most one play
 * of it at a time. Script threads use it one at a time, so it needs no locking.
 *
 * Its play position is counted on the runtime clock in milliseconds of the sound at normal pitch:
 * [played] up to [since], then [rate] times the time after that. A new mix starts a new stretch,
 * so the pitch the sound had at every moment counts.
 */
class SoundPlayer internal constructor() {
    /** The target that played the sound last, whose volume and effects it has. */
    internal var target: Target? = null
        private set

    /** The frame the sound last started in. */
    internal var startFrame = -1L
        private set

    /** Bumped every time the sound stops or starts over, which ends "play sound until done". */
    var stops = 0
        internal set

    private var length = 0.0
    private var played = 0.0
    private var since = 0.0
    private var rate = 1.0
    private var stopped = true
    private var audio: Sounds.Audio? = null

    fun isPlaying(now: Double): Boolean = !stopped && played + (now - since) * rate < length

    /** Whether a wait that began when [stops] had this value is over at [now]. */
    fun isFinished(stops: Int, now: Double): Boolean = this.stops != stops || !isPlaying(now)

    internal fun start(target: Target, length: Double, now: Double, frame: Long, rate: Double, audio: Sounds.Audio?) {
        this.target = target
        this.length = length
        played = 0.0
        since = now
        this.rate = rate
        startFrame = frame
        stopped = false
        this.audio = audio
    }

    internal fun remix(mix: SoundMix, now: Double) {
        audio?.remix(mix)
        if (mix.rate != rate) {
            played += (now - since) * rate
            since = now
            rate = mix.rate
        }
    }

    internal fun stop() {
        if (stopped) return
        stopped = true
        stops++
        audio?.stop()
        audio = null
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

    @Volatile private var fadeRequested = false
    private val fadeFrames = Math.max(1, (sampleRate * FADE_SECONDS).toInt())

    /** Frames left in the fade out, or -1 before it starts. */
    private var fadeLeft = -1

    /** Fades the sound out linearly over 25 ms, after which [read] reports its end. */
    fun fadeOut() {
        fadeRequested = true
    }

    /** Fills [out] with up to a quarter of its size in stereo frames. Fewer means the sound ended. */
    fun read(out: ByteArray): Int {
        val mix = mix
        val max = out.size / 4
        var n = 0
        while (n < max) {
            if (fadeLeft < 0 && fadeRequested) fadeLeft = fadeFrames
            if (fadeLeft == 0) break
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
            if (fadeLeft > 0) {
                val fade = fadeLeft.toDouble() / fadeFrames
                l *= fade
                r *= fade
                fadeLeft--
            }
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
        const val FADE_SECONDS = 0.025
    }
}
