plugins {
    id("raftlab.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api(project(":raftlab-trace"))
    api(libs.kotlinx.serialization.json)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.kotest.assertions.core)
}
