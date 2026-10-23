import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
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
}
