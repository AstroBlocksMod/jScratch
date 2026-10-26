// Sample Scratch projects. Each directory under src/ is zipped into an .sb3, and
// each one under sb2/ into a Scratch 2 .sb2, so the sources stay reviewable in git.
// A Scratch 1.4 .sb is a binary Squeak object graph that cannot be written by
// hand, so that one is fetched from scratch-sb1-converter's test fixtures.

description = "Sample projects built from reviewable sources."

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

// Projects saved by Scratch 2 and Scratch 1.4, for the converter.

val sb2Tasks =
    layout.projectDirectory
        .dir("sb2")
        .asFile
        .listFiles { f -> f.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()
        .map { dir ->
            tasks.register<Zip>("sb2Sample${dir.name.replaceFirstChar { it.uppercase() }}") {
                group = "build"
                description = "Packs samples/sb2/${dir.name} into an .sb2 file."
                from(dir)
                archiveFileName.set("${dir.name}.sb2")
                destinationDirectory.set(layout.buildDirectory.dir("sb2"))
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
        }

val sb1Deps = configurations.dependencyScope("sb1Deps")
val sb1 = configurations.resolvable("sb1") { extendsFrom(sb1Deps.get()) }

dependencies {
    add(sb1Deps.name, variantOf(libs.sb1.default.project) { artifactType("sb") })
}

val sb1Sample =
    tasks.register<Sync>("sb1Sample") {
        group = "build"
        description = "Fetches the default Scratch 1.4 project from scratch-sb1-converter's test fixtures."
        from(sb1)
        rename { "default.sb" }
        into(layout.buildDirectory.dir("sb1"))
    }

val oldProjectElements = configurations.consumable("oldProjectElements")

sb2Tasks.forEach { task ->
    artifacts.add(oldProjectElements.name, task)
}
artifacts.add(oldProjectElements.name, layout.buildDirectory.file("sb1/default.sb")) {
    builtBy(sb1Sample)
}

tasks.register("assemble") {
    dependsOn(buildSamples, sb2Tasks, sb1Sample)
}
tasks.register("build") {
    dependsOn("assemble")
}
tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
