package org.jscratch.runtime

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Microphone loudness for the `loudness` block and the `when loudness greater than` hat, measured
 * the way scratch-audio does it.
 *
 * Like scratch-audio, the microphone is opened the first time a project asks for the loudness, and
 * the loudness is -1 until it is open, or forever when there is no microphone to open. A daemon
 * thread keeps reading from it into a ring buffer that holds the last [WINDOW] samples, the
 * stretch a Web Audio analyser looks at.
 *
 * When the operating system does not let the program use the microphone, macOS for one hands out
 * nothing but zeros instead of an error. A real microphone always picks up some noise, so the
 * loudness also stays -1, as in Scratch when the browser may not listen, until the first sample
 * that is not exactly zero.
 */
class Microphone {
    private val samples = FloatArray(WINDOW)
    private var next = 0

    @Volatile private var open = false

    /** Whether the microphone delivered anything but exact silence yet. */
    @Volatile private var heard = false

    @Volatile private var openedNanos = 0L
    private var warned = false
    private var connecting = false
    private var last = 0.0

    /** The loudness from 0 to 100, or -1 while there is no microphone. Called from the scheduler only. */
    fun loudness(): Double {
        if (!connecting) {
            connecting = true
            Thread
                .ofPlatform()
                .daemon()
                .name("jscratch-microphone")
                .start { record() }
        }
        if (!open) return -1.0
        if (!heard) {
            if (!warned && System.nanoTime() - openedNanos > SILENCE_NANOS) {
                warned = true
                Log.warn(
                    "The microphone only gives silence, so loudness stays at -1. The system may not let this " +
                        "program listen: on macOS, turn on microphone access for the app that started Java, " +
                        "such as the terminal or editor, in System Settings under Privacy & Security.",
                )
            }
            return -1.0
        }
        var sum = 0.0
        synchronized(samples) {
            for (s in samples) sum += s * s
        }
        return level(Math.sqrt(sum / WINDOW))
    }

    /**
     * Turns the RMS of the window into the loudness: scratch-audio lets the value fall slowly, scales
     * it up so quiet sounds count, then maps it to a whole number from 0 to 100.
     */
    internal fun level(rms: Double): Double {
        val smoothed = Math.max(rms, last * 0.6)
        last = smoothed
        return Math.min(Math.round(Math.sqrt(smoothed * 1.63) * 100).toDouble(), 100.0)
    }

    private fun record() {
        val line = openLine() ?: return
        val format = line.format
        val channels = format.channels
        val frame = format.frameSize
        val buffer = ByteArray(frame * 512)
        openedNanos = System.nanoTime()
        open = true
        while (true) {
            val n = line.read(buffer, 0, buffer.size)
            if (n <= 0) {
                open = false
                return
            }
            synchronized(samples) {
                var i = 0
                while (i + frame <= n) {
                    // A Web Audio analyser mixes stereo down to mono as the average of the channels.
                    var mono = 0f
                    for (c in 0 until channels) {
                        val lo = buffer[i + 2 * c].toInt() and 0xFF
                        val hi = buffer[i + 2 * c + 1].toInt()
                        val sample = (hi shl 8) or lo
                        if (sample != 0) heard = true
                        mono += sample / 32768f
                    }
                    samples[next] = mono / channels
                    next = (next + 1) % WINDOW
                    i += frame
                }
            }
        }
    }

    /** The first 16-bit format the default microphone accepts, already recording, or null when none does. */
    private fun openLine(): TargetDataLine? {
        for (rate in floatArrayOf(48000f, 44100f)) {
            for (channels in 1..2) {
                val format = AudioFormat(rate, 16, channels, true, false)
                try {
                    val line = AudioSystem.getTargetDataLine(format)
                    line.open(format)
                    line.start()
                    return line
                } catch (_: Exception) {
                    // try the next format
                }
            }
        }
        Log.warn("No microphone found; loudness stays at -1.")
        return null
    }

    companion object {
        /** The size of a Web Audio analyser's default window, which scratch-audio measures over. */
        const val WINDOW = 2048

        /** How long the microphone may give only silence before the log says why loudness stays -1. */
        private const val SILENCE_NANOS = 2_000_000_000L
    }
}
