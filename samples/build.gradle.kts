// Sample Scratch projects. Each directory under src/ is zipped into an .sb3
// so the sources stay reviewable in git.

description = "Sample .sb3 projects built from reviewable sources."

val sampleDirs =
    layout.projectDirectory
        .dir("src")
        .asFile
        .listFiles { f -> f.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()

val sampleTasks =
    sampleDirs.map { dir ->
        tasks.register<Zip>("sample${dir.name.replaceFirstChar { it.uppercase() }}") {
            group = "build"
            description = "Packs samples/src/${dir.name} into an .sb3 file."
            from(dir)
            archiveFileName.set("${dir.name}.sb3")
            destinationDirectory.set(layout.buildDirectory.dir("sb3"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }
    }

val buildSamples =
    tasks.register("buildSamples") {
        group = "build"
        description = "Builds every sample .sb3."
        dependsOn(sampleTasks)
    }

val sb3Elements = configurations.consumable("sb3Elements")

sampleTasks.forEach { task ->
    artifacts.add(sb3Elements.name, task)
}

tasks.register("assemble") {
    dependsOn(buildSamples)
}
tasks.register("build") {
    dependsOn("assemble")
}
tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
