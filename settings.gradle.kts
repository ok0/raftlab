pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "raftlab"
include(":raftlab-trace", ":raftlab-core", ":raftlab-sim", ":raftlab-viz")
