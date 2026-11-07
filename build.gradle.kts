import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.teavm) apply false
}

allprojects {
    group = "org.jscratch"
    version = "0.1.0-SNAPSHOT"
}

val ktlintVersion = libs.versions.ktlint.cli.get()

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                // Compile against the JDK 21 class library whatever JDK runs the
                // build, like options.release does for Java, so a JAR built on a
                // newer JDK never calls a method that 21 lacks.
                freeCompilerArgs.add("-Xjdk-release=21")
                allWarningsAsErrors.set(false)
            }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
        }
        extensions.configure<KtlintExtension> {
            version.set(ktlintVersion)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }
    // The multiplatform module (sb3o) configures its own targets, but is linted like the rest.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<KtlintExtension> {
            version.set(ktlintVersion)
        }
    }
}
