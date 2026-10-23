package org.jscratch.runtime

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
    val format: AudioFormat get() = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
    val seconds: Double get() = frames.toDouble() / sampleRate
}

/**
 * MP3 and WAV decoding with dr_mp3 and dr_wav. The C code in `src/main/c/decoders.c` is
 * compiled to WebAssembly and then to JVM classes (`DecodersMachine`) at build time.
 * WAV covers PCM, float, IMA and Microsoft ADPCM, A-law and mu-law.
 */
object SoundDecoders {
    private val module: WasmModule by lazy {
        val stream = SoundDecoders::class.java.getResourceAsStream("audio/Decoders.meta")
            ?: throw IllegalStateException("sound decoders are missing from the class path")
        stream.use { Parser.parse(it) }
    }

    fun decodeMp3(bytes: ByteArray): Pcm? = decode("jscratch_decode_mp3", bytes)

    fun decodeWav(bytes: ByteArray): Pcm? = decode("jscratch_decode_wav", bytes)

    /**
     * Each decode gets its own instance. Instances are cheap, the calls can run on any thread,
     * and the memory a long sound needed is not held for the rest of the run.
     */
    private fun decode(function: String, bytes: ByteArray): Pcm? {
        val wasm = newInstance()
        val memory = wasm.memory()
        val alloc = wasm.export("jscratch_alloc")
        val input = alloc.apply(bytes.size.toLong())[0].toInt()
        val info = alloc.apply(12)[0].toInt()
        if (input == 0 || info == 0) return null
        memory.write(input, bytes)

        val samples = wasm.export(function).apply(input.toLong(), bytes.size.toLong(), info.toLong())[0].toInt()
        if (samples == 0) return null
        val channels = memory.readInt(info)
        val sampleRate = memory.readInt(info + 4)
        val frames = memory.readU32(info + 8)
        val size = frames * channels * 2
        if (channels <= 0 || sampleRate <= 0 || size > Int.MAX_VALUE) return null
        return Pcm(channels, sampleRate, frames.toInt(), memory.readBytes(samples, size.toInt()))
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
