plugins {
    alias(libs.plugins.kotlin.jvm)
}

description =
    "Platform-independent compiler core: sb3 model, bytecode generator (ASM) and JAR writer. " +
    "Compiled both for the JVM and, through TeaVM, for the browser."

dependencies {
    api(libs.asm)
    api(libs.asm.util)
}
