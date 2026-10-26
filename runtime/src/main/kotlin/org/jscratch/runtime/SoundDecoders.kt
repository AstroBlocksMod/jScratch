package org.jscratch.runtime

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.WasmFunctionHandle
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import org.jscratch.runtime.audio.DecodersMachine
import javax.sound.sampled.AudioFormat

/** Decoded audio: interleaved 16-bit little-endian samples. */
class Pcm(val channels: Int, val sampleRate: Int, val frames: Int, val data: ByteArray) {
    val seconds: Double get() = frames.toDouble() / sampleRate
}

fun audioFormat(sampleRate: Int, channels: Int): AudioFormat =
    AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

/**
 * An open MP3 or WAV decoder (dr_mp3 or dr_wav) that hands out 16-bit PCM in chunks. The C code
 * in `src/main/c/decoders.c` is compiled to WebAssembly and then to JVM classes
 * (`DecodersMachine`) at build time. WAV covers PCM, float, IMA and Microsoft ADPCM, A-law and
 * mu-law.
 *
 * Every stream has its own WebAssembly instance, so streams can be used from different threads,
 * one thread per stream, and close gives all of its memory back.
 */
class SoundStream private constructor(
    private val wasm: Instance,
    private val decoder: Int,
    private val prefix: String,
    val channels: Int,
    val sampleRate: Int,
    /** Length in frames as reported by the file, before decoding. */
    val frames: Long,
) : AutoCloseable {
    private val read: ExportFunction = wasm.export("${prefix}_read")
    private var buffer = 0
    private var bufferFrames = 0

    val seconds: Double get() = frames.toDouble() / sampleRate

    /** Decodes up to [maxFrames] frames. Returns an empty array at the end of the sound. */
    fun read(maxFrames: Int): ByteArray {
        if (maxFrames > bufferFrames) {
            if (buffer != 0) wasm.export("jscratch_free").apply(buffer.toLong())
            buffer = wasm.export("jscratch_alloc").apply(maxFrames.toLong() * channels * 2)[0].toInt()
            if (buffer == 0) {
                bufferFrames = 0
                return ByteArray(0)
            }
            bufferFrames = maxFrames
        }
        val got = read.apply(decoder.toLong(), buffer.toLong(), maxFrames.toLong())[0].toInt()
        return wasm.memory().readBytes(buffer, got * channels * 2)
    }

    /** Frees the decoder. The instance with the file bytes in its memory is then dropped as a whole. */
    override fun close() {
        wasm.export("${prefix}_close").apply(decoder.toLong())
    }

    companion object {
        private val module: WasmModule by lazy {
            val stream = SoundStream::class.java.getResourceAsStream("audio/Decoders.meta")
                ?: throw IllegalStateException("sound decoders are missing from the class path")
            stream.use { Parser.parse(it) }
        }

        fun openMp3(bytes: ByteArray): SoundStream? = open("jscratch_mp3", bytes)

        fun openWav(bytes: ByteArray): SoundStream? = open("jscratch_wav", bytes)

        private fun open(prefix: String, bytes: ByteArray): SoundStream? {
            val wasm = newInstance()
            val alloc = wasm.export("jscratch_alloc")
            // The decoder reads the file from this buffer until it is closed.
            val input = alloc.apply(bytes.size.toLong())[0].toInt()
            val info = alloc.apply(12)[0].toInt()
            if (input == 0 || info == 0) return null
            val memory = wasm.memory()
            memory.write(input, bytes)

            val decoder = wasm.export("${prefix}_open").apply(input.toLong(), bytes.size.toLong(), info.toLong())[0]
            if (decoder == 0L) return null
            val channels = memory.readInt(info)
            val sampleRate = memory.readInt(info + 4)
            val frames = memory.readU32(info + 8)
            if (channels <= 0 || sampleRate <= 0) return null
            return SoundStream(wasm, decoder.toInt(), prefix, channels, sampleRate, frames)
        }

        private fun newInstance(): Instance {
            // Emscripten reports memory growth to its JavaScript glue, which this module has none of.
            val memoryGrowth =
                HostFunction(
                    "env",
                    "emscripten_notify_memory_growth",
                    FunctionType.accepting(ValType.I32),
                    WasmFunctionHandle { _, _ -> null },
                )
            val instance =
                Instance
                    .builder(module)
                    .withImportValues(ImportValues.builder().addFunction(memoryGrowth).build())
                    .withMachineFactory(::DecodersMachine)
                    .build()
            instance.export("_initialize").apply()
            return instance
        }
    }
}

/** Whole-file decoding on top of [SoundStream]. */
object SoundDecoders {
    private const val CHUNK_FRAMES = 16384

    fun decodeMp3(bytes: ByteArray): Pcm? = SoundStream.openMp3(bytes)?.use { decodeAll(it) }

    fun decodeWav(bytes: ByteArray): Pcm? = SoundStream.openWav(bytes)?.use { decodeAll(it) }

    private fun decodeAll(stream: SoundStream): Pcm? {
        val bytesPerFrame = stream.channels * 2
        if (stream.frames * bytesPerFrame > Int.MAX_VALUE) return null
        // Sized from the reported length. The decoded length can differ slightly, either way.
        var data = ByteArray((stream.frames * bytesPerFrame).toInt())
        var size = 0
        while (true) {
            val chunk = stream.read(CHUNK_FRAMES)
            if (chunk.isEmpty()) break
            if (size + chunk.size > data.size) data = data.copyOf(maxOf(size + chunk.size, data.size * 2))
            chunk.copyInto(data, size)
            size += chunk.size
        }
        if (size == 0) return null
        if (size < data.size) data = data.copyOf(size)
        return Pcm(stream.channels, stream.sampleRate, size / bytesPerFrame, data)
    }
}
