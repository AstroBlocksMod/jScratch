package org.jscratch.compiler

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Reading and writing project archives with `java.util.zip`, which works on the JVM and under TeaVM. */
object Archives {
    /** True for the bytes of a zip archive. */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /**
     * Every file in a zip archive by name, in archive order. Some tools nest everything in a
     * folder, so only the file name is kept.
     */
    fun read(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                entries[entry.name.substringAfterLast('/')] = out.toByteArray()
            }
        }
        return entries
    }

    /** A project archive as Scratch 2 and 3 write them: `project.json` first, then the assets. */
    fun writeProject(projectJson: ByteArray, assets: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(projectJson)
            zip.closeEntry()
            for ((name, data) in assets) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
