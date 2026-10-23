import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.node)
}

description = "scratch-parser bundled with esbuild into one script for the browser and for Rhino."

node {
    version.set("26.12.0")
    download.set(true)
    // The Node.js repository is declared in settings.gradle.kts.
    distBaseUrl.set(null as String?)
    npmInstallCommand.set("ci")
}

val bundleFile = layout.buildDirectory.file("dist/jscratch-sb3.js")

val bundle =
    tasks.register<NpmTask>("bundle") {
        group = "build"
        description = "Bundles src/index.js and scratch-parser into build/dist/jscratch-sb3.js."
        dependsOn(tasks.npmInstall)
        args.set(listOf("run", "build"))
        inputs.files("package.json", "package-lock.json")
        inputs.dir("src")
        outputs.file(bundleFile)
    }

val sb3JsElements = configurations.consumable("sb3JsElements")

artifacts {
    add(sb3JsElements.name, bundleFile) {
        builtBy(bundle)
    }
}

tasks.register("assemble") {
    dependsOn(bundle)
}
tasks.register("build") {
    dependsOn("assemble")
}
tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
