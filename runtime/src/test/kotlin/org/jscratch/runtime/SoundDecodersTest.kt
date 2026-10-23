package org.jscratch.runtime

import java.io.DataInputStream
import java.net.JarURLConnection
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundDecodersTest {
    private fun bytes(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/sounds/$name"), "missing test sound $name").use { it.readBytes() }

    /** Largest absolute sample value, so a decoder that returns silence fails. */
    private fun peak(pcm: Pcm): Int {
        var max = 0
        for (i in 0 until pcm.data.size / 2) {
            val sample = (pcm.data[2 * i].toInt() and 0xff) or (pcm.data[2 * i + 1].toInt() shl 8)
            max = maxOf(max, abs(sample.toShort().toInt()))
        }
        return max
    }

    @Test
    fun decodesMp3() {
        val pcm = assertNotNull(SoundDecoders.decodeMp3(bytes("beep.mp3")))
        assertEquals(1, pcm.channels)
        assertEquals(44100, pcm.sampleRate)
        assertEquals(0.5, pcm.seconds, 0.05)
        assertTrue(peak(pcm) > 2000, "decoded MP3 is silent")
    }

    @Test
    fun decodesImaAdpcmWav() {
        val pcm = assertNotNull(SoundDecoders.decodeWav(bytes("boop.wav")))
        assertEquals(1, pcm.channels)
        assertEquals(22050, pcm.sampleRate)
        // 0.3 s of audio in 1024-byte blocks of 2041 frames each. dr_wav decodes whole blocks and
        // ignores the "fact" chunk, so the padding at the end of the last block is part of the sound.
        assertEquals(4 * 2041, pcm.frames)
        assertTrue(peak(pcm) > 2000, "decoded ADPCM is silent")
    }

    @Test
    fun decodesPcmWavLikeTheJdk() {
        val file = bytes("tone.wav")
        val pcm = assertNotNull(SoundDecoders.decodeWav(file))
        val jdk = AudioSystem.getAudioInputStream(file.inputStream()).use { it.readBytes() }
        assertEquals(2, pcm.channels)
        assertEquals(48000, pcm.sampleRate)
        assertContentEquals(jdk, pcm.data)
    }

    @Test
    fun streamsInChunks() {
        val file = bytes("beep.mp3")
        val whole = assertNotNull(SoundDecoders.decodeMp3(file))
        val stream = assertNotNull(SoundStream.openMp3(file))
        val streamed =
            stream.use {
                assertEquals(whole.frames.toLong(), it.frames, "reported length differs from the decoded one")
                generateSequence { it.read(1000).takeIf { chunk -> chunk.isNotEmpty() } }
                    .fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            }
        assertContentEquals(whole.data, streamed)
    }

    @Test
    fun rejectsGarbage() {
        val garbage = ByteArray(1000) { (it * 31).toByte() }
        assertNull(SoundDecoders.decodeWav(garbage))
        assertNull(SoundDecoders.decodeMp3(ByteArray(0)))
        assertNull(SoundStream.openMp3(garbage))
    }

    /**
     * HotSpot does not JIT-compile methods over 8000 bytes of bytecode, so a decoder function
     * that grows past that runs in the interpreter, several times slower. The emcc flags in
     * runtime/build.gradle.kts keep the functions small. This catches a dr_libs update or a
     * flag change that undoes that.
     */
    @Test
    fun generatedMethodsAreSmallEnoughToJit() {
        val meta = assertNotNull(javaClass.classLoader.getResource("org/jscratch/runtime/audio/Decoders.meta"))
        val jar = (meta.openConnection() as JarURLConnection).jarFile
        val classes = jar.entries().toList().filter { it.name.endsWith(".class") }
        assertTrue(classes.isNotEmpty(), "no generated decoder classes in ${jar.name}")
        for (entry in classes) {
            val sizes = codeSizes(jar.getInputStream(entry).use { it.readBytes() })
            for ((method, size) in sizes) {
                assertTrue(size <= 8000, "${entry.name} $method has $size bytes of bytecode, more than 8000")
            }
        }
    }

    /** Bytecode length of every method in a class file, by name and descriptor so overloads stay apart. */
    private fun codeSizes(bytes: ByteArray): Map<String, Int> {
        val data = DataInputStream(bytes.inputStream())
        data.skipBytes(8)
        val utf8 = HashMap<Int, String>()
        val poolSize = data.readUnsignedShort()
        var i = 1
        while (i < poolSize) {
            when (val tag = data.readUnsignedByte()) {
                1 -> utf8[i] = data.readUTF()
                3, 4 -> data.skipBytes(4)
                5, 6 -> data.skipBytes(8).also { i++ }
                7, 8, 16, 19, 20 -> data.skipBytes(2)
                9, 10, 11, 12, 17, 18 -> data.skipBytes(4)
                15 -> data.skipBytes(3)
                else -> error("unknown constant pool tag $tag")
            }
            i++
        }
        data.skipBytes(6)
        data.skipBytes(2 * data.readUnsignedShort())
        repeat(data.readUnsignedShort()) {
            data.skipBytes(6)
            repeat(data.readUnsignedShort()) {
                data.skipBytes(2)
                data.skipBytes(data.readInt())
            }
        }

        val sizes = HashMap<String, Int>()
        repeat(data.readUnsignedShort()) {
            data.skipBytes(2)
            val name = utf8.getValue(data.readUnsignedShort()) + utf8.getValue(data.readUnsignedShort())
            repeat(data.readUnsignedShort()) {
                val attribute = utf8.getValue(data.readUnsignedShort())
                val length = data.readInt()
                if (attribute == "Code") {
                    data.skipBytes(4)
                    sizes[name] = data.readInt()
                    data.skipBytes(length - 8)
                } else {
                    data.skipBytes(length)
                }
            }
        }
        return sizes
    }
}
