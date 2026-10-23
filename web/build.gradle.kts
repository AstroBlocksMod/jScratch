import org.teavm.gradle.api.OptimizationLevel

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.teavm)
}

description = "Experimental browser playground: the compiler translated to JavaScript with TeaVM."

val runtimeJarDeps = configurations.dependencyScope("runtimeJarDeps")
val runtimeJar = configurations.resolvable("runtimeJar") { extendsFrom(runtimeJarDeps.get()) }
val sb3JsDeps = configurations.dependencyScope("sb3JsDeps")
val sb3Js = configurations.resolvable("sb3Js") { extendsFrom(sb3JsDeps.get()) }

dependencies {
    implementation(project(":compiler"))
    implementation(libs.teavm.jso.apis)
    add(runtimeJarDeps.name, project(mapOf("path" to ":runtime", "configuration" to "fatJarElements")))
    add(sb3JsDeps.name, project(mapOf("path" to ":sb3js", "configuration" to "sb3JsElements")))
}

teavm.js {
    mainClass.set("org.jscratch.web.PlaygroundKt")
    targetFileName.set("jscratch.js")
    obfuscated.set(false)
    sourceMap.set(true)
    optimization.set(OptimizationLevel.BALANCED)
}

// Static site: the handwritten page, the TeaVM output, the scratch-parser bundle
// and the runtime JAR the page fetches and hands to the compiler.
val assembleSite =
    tasks.register<Sync>("assembleSite") {
        group = "build"
        description = "Assembles the playground into build/site."
        dependsOn(tasks.named("generateJavaScript"))
        from(layout.projectDirectory.dir("src/main/webapp"))
        from(layout.buildDirectory.dir("generated/teavm/js"))
        from(sb3Js)
        from(runtimeJar) {
            rename { "jscratch-runtime.jar" }
        }
        into(layout.buildDirectory.dir("site"))
    }

tasks.named("assemble") {
    dependsOn(assembleSite)
}
