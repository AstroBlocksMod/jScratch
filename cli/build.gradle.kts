plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Command line interface: jscratch <project.sb3> -o <out.jar>."

val runtimeJarDeps = configurations.dependencyScope("runtimeJarDeps")
val runtimeJar = configurations.resolvable("runtimeJar") { extendsFrom(runtimeJarDeps.get()) }

dependencies {
    implementation(project(":compiler-jvm"))
    add(runtimeJarDeps.name, project(mapOf("path" to ":runtime", "configuration" to "fatJarElements")))
}

application {
    mainClass.set("org.jscratch.cli.MainKt")
    applicationName = "jscratch"
}

// The runtime JAR travels inside the CLI as a resource so a compiled project
// never needs anything but the CLI distribution.
tasks.processResources {
    from(runtimeJar) {
        rename { "jscratch-runtime.jar" }
        into("org/jscratch/cli")
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

// Single-file distribution of the CLI itself (compiler + ASM + Rhino + embedded runtime).
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "distribution"
        description = "Assembles the CLI and all of its dependencies into one runnable JAR."
        archiveClassifier.set("all")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes("Main-Class" to application.mainClass.get())
        }
        from(sourceSets.main.get().output)
        dependsOn(configurations.runtimeClasspath)
        from({
            configurations.runtimeClasspath
                .get()
                .filter { it.name.endsWith(".jar") }
                .map { zipTree(it) }
        })
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**")
    }

tasks.named("assemble") {
    dependsOn(fatJar)
}
