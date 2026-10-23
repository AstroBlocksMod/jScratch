package org.jscratch.compiler

import org.jscratch.compiler.codegen.GeneratedProject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Assembles the executable JAR: manifest, generated classes, list data,
 * project assets and (optionally) every entry of the runtime JAR. Written with
 * `java.util.zip` only so the same code runs under TeaVM.
 */
object JarWriter {
    fun write(generated: GeneratedProject, assets: Map<String, ByteArray>, runtimeJar: ByteArray?): ByteArray {
        val bytes = ByteArrayOutputStream()
        val written = HashSet<String>()
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, data: ByteArray) {
                if (!written.add(name)) return
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }

            val mainClass = generated.mainClass.replace('/', '.')
            val manifest = "Manifest-Version: 1.0\r\nMain-Class: $mainClass\r\nCreated-By: jScratch\r\n\r\n"
            put("META-INF/MANIFEST.MF", manifest.toByteArray(Charsets.UTF_8))

            for ((internalName, data) in generated.classes) put("$internalName.class", data)
            for ((path, data) in generated.resources) put(path, data)
            for ((name, data) in assets) put("assets/$name", data)

            if (runtimeJar != null) {
                ZipInputStream(ByteArrayInputStream(runtimeJar)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name == "META-INF/MANIFEST.MF" || name.endsWith(".SF") || name.endsWith(".RSA") ||
                            name.endsWith(".DSA")
                        ) {
                            continue
                        }
                        put(name, input.readBytes())
                    }
                }
            }
        }
        return bytes.toByteArray()
    }
}
