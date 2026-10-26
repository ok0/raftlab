plugins {
    id("raftlab.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
