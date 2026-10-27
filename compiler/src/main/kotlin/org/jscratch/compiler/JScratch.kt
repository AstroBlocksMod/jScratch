package org.jscratch.compiler

import org.jscratch.compiler.codegen.ProjectCompiler
import org.jscratch.compiler.json.ProjectJson
import org.jscratch.compiler.sb3.Project
import org.jscratch.compiler.sb3.Sb3Parser
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.io.StringWriter

class CompileResult(
    /** The executable JAR (or a classes-only JAR when no runtime was supplied). */
    val jar: ByteArray,
    /** Generated class files by internal name. */
    val classes: Map<String, ByteArray>,
    val project: Project,
    val warnings: List<String>,
)

/** Settings that change what a project sees about the player it runs in. */
class CompileOptions(
    /**
     * Whether the hacked `is turbowarp?` and `is compiled?` blocks report true. They are boolean argument
     * reporters dragged out of a custom block, which report false in Scratch but true in TurboWarp, so
     * projects use them to tell which player they run in. Some refuse to run without TurboWarp.
     */
    val turboWarp: Boolean = false,
)

/** Entry point shared by the CLI, the tests and the browser playground. */
object JScratch {
    /**
     * Compiles an .sb3 archive. [runtimeJar] is the runtime's fat JAR whose entries
     * are merged into the output; pass null for a classes-only JAR. [json] supplies
     * the platform's JSON parsing and the scratch-parser bridge.
     */
    fun compile(
        sb3: ByteArray,
        runtimeJar: ByteArray?,
        json: ProjectJson,
        options: CompileOptions = CompileOptions(),
    ): CompileResult {
        val file = Sb3Parser(json).readSb3(sb3)
        val generated = ProjectCompiler.compile(file.project, options)
        val warnings = ArrayList(generated.warnings)
        val usedAssets = LinkedHashMap<String, ByteArray>()
        for (t in file.project.targets) {
            for (c in t.costumes) {
                val data = file.assets[c.md5ext]
                if (data != null) {
                    usedAssets[c.md5ext] = data
                } else {
                    warnings.add("Costume asset ${c.md5ext} ('${c.name}' of '${t.name}') is missing from the archive")
                }
            }
            for (s in t.sounds) {
                val data = file.assets[s.md5ext]
                if (data != null) {
                    usedAssets[s.md5ext] = data
                } else {
                    warnings.add("Sound asset ${s.md5ext} ('${s.name}' of '${t.name}') is missing from the archive")
                }
            }
        }
        val jar = JarWriter.write(generated, usedAssets, runtimeJar)
        return CompileResult(jar, generated.classes, file.project, warnings)
    }

    /** Human-readable bytecode listing of a class file (ASM Textifier). */
    fun disassemble(classFile: ByteArray): String {
        val out = StringWriter()
        ClassReader(classFile).accept(TraceClassVisitor(PrintWriter(out)), ClassReader.SKIP_DEBUG)
        return out.toString()
    }
}
