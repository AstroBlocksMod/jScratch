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
        // Node.js downloads for the node plugin. Declared here instead of by the plugin itself,
        // because a project repository would hide the ones above.
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("org.nodejs", "node")
            }
        }
    }
}

include("runtime", "sb3js", "compiler", "compiler-jvm", "cli", "web", "samples", "benchmarks")
