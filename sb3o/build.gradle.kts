import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

description =
    "SB3O: reads, analyzes, optimizes and writes Scratch 3 projects. A Kotlin Multiplatform library " +
    "for the JVM and JavaScript, under the BSD 3-Clause License (see sb3o/LICENSE)."

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            // Like the other modules: compile against the JDK 21 class library whatever JDK runs the build.
            freeCompilerArgs.add("-Xjdk-release=21")
        }
    }
    js {
        outputModuleName.set("sb3o")
        nodejs()
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// The JVM tests round-trip the sample projects, read straight from their source directories.
val samplesSrc = layout.projectDirectory.dir("../samples/src")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.dir(samplesSrc)
    systemProperty("sb3o.test.samplesSrc", samplesSrc.asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
