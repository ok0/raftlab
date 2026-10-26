plugins {
    id("org.jetbrains.dokka")
}

dokka {
    dokkaPublications.html {
        failOnWarning.set(true)
    }
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
    }
}
