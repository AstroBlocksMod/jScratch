import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.node)
}

description = "scratch-parser and the Scratch 1.4 and 2 converter, bundled for the browser and for Rhino."

node {
    version.set("26.12.0")
    download.set(true)
    // The Node.js repository is declared in settings.gradle.kts.
    distBaseUrl.set(null as String?)
    npmInstallCommand.set("ci")
}

// jscratch-sb3.js parses every project; jscratch-convert.js converts Scratch 1.4 and 2 projects.
val bundleFiles =
    listOf("jscratch-sb3.js", "jscratch-convert.js").map { layout.buildDirectory.file("dist/$it") }

val bundle =
    tasks.register<NpmTask>("bundle") {
        group = "build"
        description = "Bundles src/index.js and src/convert.js with their dependencies into build/dist (see build.mjs)."
        dependsOn(tasks.npmInstall)
        args.set(listOf("run", "build"))
        inputs.files("package.json", "package-lock.json", "build.mjs")
        inputs.dir("src")
        outputs.files(bundleFiles)
    }

val sb3JsElements = configurations.consumable("sb3JsElements")

artifacts {
    for (file in bundleFiles) {
        add(sb3JsElements.name, file) {
            builtBy(bundle)
        }
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
