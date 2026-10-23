import com.github.gradle.node.task.NodeTask

// CPU-heavy projects timed on jScratch, scratch-vm and TurboWarp. Nothing here runs
// as part of `build`: `./gradlew :benchmarks:benchmark` runs all three engines and
// writes build/reports/benchmarks/report.md. Each engine also has its own task.
//
//   -Pbenchmark.runs=<n>         runs per project and engine, default 3
//   -Pbenchmark.only=<a,b,...>   only these projects

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.node)
}

description = "CPU-heavy projects timed on jScratch, scratch-vm and TurboWarp."

val runtimeJarDeps = configurations.dependencyScope("runtimeJarDeps")
val runtimeJar = configurations.resolvable("runtimeJar") { extendsFrom(runtimeJarDeps.get()) }

dependencies {
    implementation(project(":compiler-jvm"))
    implementation(libs.kotlinx.serialization.json)
    add(runtimeJarDeps.name, project(mapOf("path" to ":runtime", "configuration" to "fatJarElements")))
}

node {
    version.set("26.12.0")
    download.set(true)
    distBaseUrl.set(null as String?)
    npmInstallCommand.set("ci")
    nodeProjectDir.set(layout.projectDirectory.dir("js"))
}

val projectsDir = layout.projectDirectory.dir("projects")
val sb3Dir = layout.buildDirectory.dir("sb3")
val resultsDir = layout.buildDirectory.dir("benchmarks")
val runs = providers.gradleProperty("benchmark.runs").orElse("3")
val only = providers.gradleProperty("benchmark.only").orElse("")
val commit =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim() }

// Each directory under projects/ becomes an .sb3, like the samples. The costumes in
// assets/ are shared by every project, so they are added to each archive.
val assetsDir = layout.projectDirectory.dir("assets")
val projectTasks =
    projectsDir.asFile
        .listFiles { f -> f.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()
        .map { dir ->
            tasks.register<Zip>("benchmarkProject${dir.name.replaceFirstChar { it.uppercase() }}") {
                group = "build"
                description = "Packs projects/${dir.name} into an .sb3 file."
                from(dir)
                from(assetsDir)
                exclude("benchmark.properties")
                archiveFileName.set("${dir.name}.sb3")
                destinationDirectory.set(sb3Dir)
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
        }

val buildProjects =
    tasks.register("buildBenchmarkProjects") {
        group = "build"
        description = "Builds every benchmark .sb3."
        dependsOn(projectTasks)
    }

tasks.named("assemble") {
    dependsOn(buildProjects)
}

val benchmarkReport =
    tasks.register<JavaExec>("benchmarkReport") {
        group = "benchmark"
        description = "Writes build/reports/benchmarks/report.md from the latest results of each engine."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.jscratch.benchmarks.ReportKt")
        args(
            projectsDir.asFile.path,
            resultsDir.get().asFile.path,
            layout.buildDirectory
                .file("reports/benchmarks/report.md")
                .get()
                .asFile.path,
        )
        outputs.upToDateWhen { false }
    }

val benchmarkJScratch =
    tasks.register<JavaExec>("benchmarkJScratch") {
        group = "benchmark"
        description = "Compiles the benchmark projects and times them headless on jScratch."
        dependsOn(buildProjects)
        inputs.files(runtimeJar)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.jscratch.benchmarks.JScratchBenchmarkKt")
        // Only plain values and providers in the argument list, so the configuration cache can store it.
        val runtimeJarPath = runtimeJar.map { it.singleFile.path }
        val fixedArgs =
            listOf(sb3Dir.get().asFile.path, resultsDir.get().file("jscratch.json").asFile.path, runs.get(), only.get())
        argumentProviders.add {
            listOf(fixedArgs[0], runtimeJarPath.get()) + fixedArgs.drop(1)
        }
        systemProperty("jscratch.benchmark.version", "${project.version} (${commit.getOrElse("unknown")})")
        outputs.upToDateWhen { false }
        finalizedBy(benchmarkReport)
    }

fun registerVmBenchmark(taskName: String, engine: String, label: String) = tasks.register<NodeTask>(taskName) {
    group = "benchmark"
    description = "Times the benchmark projects on $label in Node."
    dependsOn(tasks.npmInstall, buildProjects)
    script.set(layout.projectDirectory.file("js/run.js"))
    args.set(
        listOf(
            engine,
            sb3Dir.get().asFile.path,
            resultsDir.get().file("$engine.json").asFile.path,
            runs.get(),
            only.get(),
        ),
    )
    outputs.upToDateWhen { false }
    finalizedBy(benchmarkReport)
}

val benchmarkTurboWarp = registerVmBenchmark("benchmarkTurboWarp", "turbowarp", "TurboWarp's VM")
val benchmarkScratchVm = registerVmBenchmark("benchmarkScratchVm", "scratch-vm", "the Scratch Foundation's scratch-vm")

// One engine at a time: with the configuration cache, Gradle would otherwise run them side by side
// and they would compete for the CPU.
benchmarkTurboWarp.configure { mustRunAfter(benchmarkJScratch) }
benchmarkScratchVm.configure { mustRunAfter(benchmarkJScratch, benchmarkTurboWarp) }
benchmarkReport.configure {
    mustRunAfter(benchmarkJScratch, benchmarkTurboWarp, benchmarkScratchVm)
}

tasks.register("benchmark") {
    group = "benchmark"
    description = "Times every benchmark project on jScratch, TurboWarp and scratch-vm, then writes the report."
    dependsOn(benchmarkJScratch, benchmarkTurboWarp, benchmarkScratchVm)
}
