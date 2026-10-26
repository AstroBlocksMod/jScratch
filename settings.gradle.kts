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
        // Single-header C decoders from dr_libs, fetched by release tag for the runtime's sound decoders.
        ivy {
            name = "dr_libs"
            setUrl("https://raw.githubusercontent.com/mackron/dr_libs/")
            patternLayout {
                artifact("[revision]/[module].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeGroup("io.github.mackron")
            }
        }
        // Scratch 1.4 projects from scratch-sb1-converter's test fixtures, fetched by commit for the samples.
        ivy {
            name = "scratch-sb1-converter fixtures"
            setUrl("https://raw.githubusercontent.com/scratchfoundation/scratch-sb1-converter/")
            patternLayout {
                artifact("[revision]/test/fixtures/valid/[module].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeGroup("io.github.scratchfoundation")
            }
        }
    }
}

include("runtime", "sb3js", "compiler", "compiler-jvm", "cli", "web", "samples", "benchmarks")
