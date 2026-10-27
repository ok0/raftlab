plugins {
    id("raftlab.kotlin-library")
}

dependencies {
    api(project(":raftlab-core"))
    api(project(":raftlab-trace"))
}
