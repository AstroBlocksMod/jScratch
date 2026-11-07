plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Command line interface of SB3O: sb3o <project.sb3> [-o <out.sb3>]."

dependencies {
    implementation(project(":sb3o"))
}

application {
    mainClass.set("org.jscratch.sb3o.cli.MainKt")
    applicationName = "sb3o"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

// Single-file distribution: the command with the library and the Kotlin standard library.
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "distribution"
        description = "Assembles the sb3o command and its dependencies into one runnable JAR."
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
