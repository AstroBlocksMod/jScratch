rootProject.name = "jscratch"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("runtime", "sb3js", "compiler", "compiler-jvm", "cli", "web", "samples")
