import java.time.Duration

plugins {
    id("raftlab.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":raftlab-kv"))
    api(project(":raftlab-core"))
    api(project(":raftlab-trace"))
    implementation(project(":raftlab-storage-file"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}

tasks.named<Test>("test") {
    timeout.set(Duration.ofMinutes(3))
    systemProperty("junit.jupiter.execution.timeout.default", "30 s")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
}
