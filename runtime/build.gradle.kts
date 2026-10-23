import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Scratch runtime bundled into every compiled JAR (scheduler, rendering, blocks)."

val drLibsDeps = configurations.dependencyScope("drLibsDeps")
val drLibs = configurations.resolvable("drLibs") { extendsFrom(drLibsDeps.get()) }
val chicoryCompilerDeps = configurations.dependencyScope("chicoryCompilerDeps")
val chicoryCompiler = configurations.resolvable("chicoryCompiler") { extendsFrom(chicoryCompilerDeps.get()) }

// The sound decoders: src/main/c/decoders.c wraps dr_mp3 and dr_wav, Emscripten compiles it to
// WebAssembly, and Chicory compiles that to JVM classes. The result is a small JAR on the
// runtime's class path, so no WebAssembly is interpreted at run time.

val decoderHeaders =
    tasks.register<Sync>("decoderHeaders") {
        description = "Collects the dr_libs headers the sound decoders include."
        from(drLibs)
        // dr_mp3-mp3-0.7.3.h becomes dr_mp3.h
        rename("""(dr_[a-z0-9]+)-.*\.h""", "\$1.h")
        val headers = layout.buildDirectory.dir("decoders/include")
        into(headers)
        val expected =
            mapOf(
                "dr_mp3.h" to libs.versions.dr.mp3.sha256.get(),
                "dr_wav.h" to libs.versions.dr.wav.sha256.get(),
            )
        inputs.property("sha256", expected)
        doLast {
            for ((name, sha256) in expected) {
                val bytes = headers.get().file(name).asFile.readBytes()
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (actual != sha256) {
                    throw GradleException(
                        "$name has SHA-256 $actual, but gradle/libs.versions.toml expects $sha256. " +
                            "If you changed the dr_libs tag, update the hash too.",
                    )
                }
            }
        }
    }

/**
 * Compiles one C file to a WebAssembly module with Emscripten, in two steps so compiling and
 * linking can use different optimization levels. emcc comes from mise.toml. Builds started
 * outside a mise-activated shell, such as from an IDE, run it through `mise exec`.
 */
abstract class EmccTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFile abstract val source: RegularFileProperty

    @get:InputDirectory abstract val includes: DirectoryProperty

    @get:Input abstract val compileArgs: ListProperty<String>

    @get:Input abstract val linkArgs: ListProperty<String>

    /** mise.toml pins the Emscripten version, so changing it compiles the decoders again. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolchain: RegularFileProperty

    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun compile() {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
        // On Windows, emcc is emcc.bat and mise is mise.exe.
        val suffixes = if (File.separatorChar == '\\') listOf(".exe", ".bat", ".cmd") else listOf("")
        fun find(name: String): String? = path
            .flatMap { dir -> suffixes.map { File(dir, name + it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
        val emcc = find("emcc")
        val mise = find("mise")
        val command =
            when {
                emcc != null -> listOf(emcc)
                mise != null -> listOf(mise, "exec", "--", "emcc")
                else -> throw GradleException(
                    "emcc was not found. Install mise and run `mise install` in the repository root " +
                        "(see docs/development.md).",
                )
            }
        val obj = File(temporaryDir, "out.o")
        exec.exec {
            commandLine(
                command + compileArgs.get() +
                    listOf("-I${includes.get().asFile}", "-c", source.get().asFile.path, "-o", obj.path),
            )
        }
        exec.exec { commandLine(command + linkArgs.get() + listOf(obj.path, "-o", output.get().asFile.path)) }
    }
}

val decodersWasm =
    tasks.register<EmccTask>("decodersWasm") {
        group = "build"
        description = "Compiles the sound decoders to WebAssembly with Emscripten."
        source.set(layout.projectDirectory.file("src/main/c/decoders.c"))
        includes.fileProvider(decoderHeaders.map { it.destinationDir })
        // HotSpot never JIT-compiles a method over 8000 bytes of bytecode, and Chicory turns each
        // WebAssembly function into one method. Inlining builds functions past that limit, which
        // made decoding several times slower. SoundDecodersTest checks the sizes.
        compileArgs.set(listOf("-O3", "-fno-inline-functions", "-DNDEBUG"))
        linkArgs.set(
            listOf(
                // -O2 and up let wasm-opt inline functions that have a single caller.
                "-O1",
                // A plain module with no JavaScript glue. The only import left is a notification
                // when memory grows.
                "-sSTANDALONE_WASM",
                "-sALLOW_MEMORY_GROWTH",
                "-sFILESYSTEM=0",
                "-sMALLOC=emmalloc",
                // Every sound gets its own instance, so start small. Memory grows as a file needs it.
                "-sINITIAL_MEMORY=1mb",
                "--no-entry",
            ),
        )
        toolchain.set(layout.settingsDirectory.file("mise.toml"))
        output.set(layout.buildDirectory.file("decoders/decoders.wasm"))
    }

val decodersJvm =
    tasks.register<JavaExec>("decodersJvm") {
        description = "Compiles the sound decoders' WebAssembly to JVM classes with Chicory."
        val wasm = decodersWasm.flatMap { it.output }
        val output = layout.buildDirectory.dir("decoders/jvm")
        // The generated Java wrapper is not needed: SoundDecoders loads the module itself.
        val unusedSources = temporaryDir
        classpath(chicoryCompiler)
        mainClass.set("com.dylibso.chicory.experimental.compiler.cli.Cli")
        inputs.file(wasm)
        outputs.dir(output)
        argumentProviders.add {
            val out = output.get().asFile.path
            listOf(
                "--prefix=org.jscratch.runtime.audio.Decoders",
                "--class-dir=$out",
                "--wasm-dir=$out",
                "--source-dir=$unusedSources",
                // Fail instead of leaving a function to the interpreter, which the runtime does not ship.
                "--interpreter-fallback=FAIL",
                wasm.get().asFile.path,
            )
        }
        doFirst { output.get().asFile.deleteRecursively() }
    }

val decodersJar =
    tasks.register<Jar>("decodersJar") {
        description = "Packs the compiled sound decoders into a JAR."
        archiveFileName.set("decoders.jar")
        destinationDirectory.set(layout.buildDirectory.dir("decoders"))
        from(decodersJvm)
    }

dependencies {
    implementation(libs.jsvg)
    implementation(libs.chicory.runtime)
    implementation(files(decodersJar))
    add(drLibsDeps.name, variantOf(libs.dr.mp3) { artifactType("h") })
    add(drLibsDeps.name, variantOf(libs.dr.wav) { artifactType("h") })
    add(chicoryCompilerDeps.name, libs.chicory.compiler.cli)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The runtime plus every dependency it needs, as a single JAR. The compiler
// copies the entries of this JAR into each compiled project JAR.
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "build"
        description = "Assembles the runtime with its dependencies into one JAR."
        archiveClassifier.set("all")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.get().output)
        dependsOn(configurations.runtimeClasspath)
        from({
            configurations.runtimeClasspath
                .get()
                .filter { it.name.endsWith(".jar") }
                .map { zipTree(it) }
        })
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.EC",
            "META-INF/MANIFEST.MF",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "module-info.class",
            "META-INF/versions/**",
        )
    }

val fatJarElements = configurations.consumable("fatJarElements")

artifacts {
    add(fatJarElements.name, fatJar)
}

tasks.named("assemble") {
    dependsOn(fatJar)
}
