package org.jscratch.sb3o

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
                entries[entry.name.substringAfterLast('/')] = readEntry(zip)
            }
        }
        return entries
    }

    /**
     * Every file in a zip archive by its full path, in archive order, for tools that write the archive
     * back and must keep it as it was: [read] would merge `a/x.png` and `b/x.png` into one entry.
     */
    fun readPaths(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries[entry.name] = readEntry(zip)
            }
        }
        return entries
    }

    /**
     * Which of the [paths] of an archive is its `project.json`: at the top, or in one folder, which is
     * where scratch-vm looks for it. Null when there is none.
     */
    fun projectJsonPath(paths: Collection<String>): String? =
        paths.firstOrNull { it == "project.json" } ?: paths.firstOrNull { p ->
            p.endsWith("/project.json") && p.indexOf('/') == p.length - "/project.json".length
        }

    /** A zip archive of [entries], by path, in their order. */
    fun write(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * The file called [name] in a zip archive, matched like [read] matches names, or null.
     * Stops at that file, so an archive that keeps it first (Scratch writes `project.json`
     * first) is not inflated any further.
     */
    fun read(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == name) return readEntry(zip)
            }
        }
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
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
