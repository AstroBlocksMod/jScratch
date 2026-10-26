plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "JVM side of the compiler: scratch-parser running in Rhino, JSON through kotlinx-serialization."

val runtimeJarDeps = configurations.dependencyScope("runtimeJarDeps")
val runtimeJar = configurations.resolvable("runtimeJar") { extendsFrom(runtimeJarDeps.get()) }
val sampleProjectDeps = configurations.dependencyScope("sampleProjectDeps")
val sampleProjects = configurations.resolvable("sampleProjects") { extendsFrom(sampleProjectDeps.get()) }
val oldProjectDeps = configurations.dependencyScope("oldProjectDeps")
val oldProjects = configurations.resolvable("oldProjects") { extendsFrom(oldProjectDeps.get()) }
val sb3JsDeps = configurations.dependencyScope("sb3JsDeps")
val sb3Js = configurations.resolvable("sb3Js") { extendsFrom(sb3JsDeps.get()) }

dependencies {
    api(project(":compiler"))
    implementation(libs.rhino)
    implementation(libs.kotlinx.serialization.json)
    add(sb3JsDeps.name, project(mapOf("path" to ":sb3js", "configuration" to "sb3JsElements")))
    add(runtimeJarDeps.name, project(mapOf("path" to ":runtime", "configuration" to "fatJarElements")))
    add(sampleProjectDeps.name, project(mapOf("path" to ":samples", "configuration" to "sb3Elements")))
    add(oldProjectDeps.name, project(mapOf("path" to ":samples", "configuration" to "oldProjectElements")))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The scratch-parser and converter bundles are loaded into Rhino from the class path.
tasks.processResources {
    from(sb3Js) {
        into("org/jscratch/compiler/jvm")
    }
}

tasks.test {
    inputs.files(runtimeJar)
    inputs.files(sampleProjects)
    inputs.files(oldProjects)
    val runtimeJarPath = runtimeJar.map { it.singleFile.absolutePath }
    val samplesDir =
        sampleProjects.map {
            it.files
                .first()
                .parentFile.absolutePath
        }
    val oldProjectPaths = oldProjects.map { files -> files.joinToString(File.pathSeparator) { it.absolutePath } }
    val javaHome = System.getProperty("java.home")
    doFirst {
        systemProperty("jscratch.test.runtimeJar", runtimeJarPath.get())
        systemProperty("jscratch.test.samplesDir", samplesDir.get())
        systemProperty("jscratch.test.oldProjects", oldProjectPaths.get())
        systemProperty("jscratch.test.javaHome", javaHome)
    }
}
