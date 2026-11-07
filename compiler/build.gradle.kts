plugins {
    alias(libs.plugins.kotlin.jvm)
}

description =
    "Platform-independent compiler core: bytecode generator (ASM) and JAR writer over the SB3O model. " +
    "Compiled both for the JVM and, through TeaVM, for the browser."

dependencies {
    // The sb3 model, reader and writer come from SB3O (its JVM artifact, which TeaVM translates too).
    api(project(":sb3o"))
    api(libs.asm)
    api(libs.asm.util)
}
