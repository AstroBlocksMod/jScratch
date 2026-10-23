plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Scratch runtime bundled into every compiled JAR (scheduler, rendering, blocks)."

dependencies {
    implementation(libs.jsvg)
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
