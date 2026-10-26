import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.the

plugins {
    base
}

val libs = the<VersionCatalogsExtension>().named("libs")

val ktlintClasspath =
    configurations.create("ktlint") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies.addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
    "ktlint",
    libs.findLibrary("ktlint-cli").get(),
) {
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
}

val patterns: List<String> =
    if (project == rootProject) {
        listOf("*.kts", "build-logic/*.kts", "build-logic/src/**/*.kt", "build-logic/src/**/*.kts", "!**/build/**")
    } else {
        listOf("src/**/*.kt", "*.kts", "!**/build/**")
    }

val ktlintCheck =
    tasks.register<JavaExec>("ktlintCheck") {
        group = "verification"
        description = "Checks Kotlin code style with ktlint"
        classpath = ktlintClasspath
        mainClass.set("com.pinterest.ktlint.Main")
        workingDir = projectDir
        args(listOf("--relative") + patterns)
    }

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Formats Kotlin code with ktlint"
    classpath = ktlintClasspath
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args(listOf("-F", "--relative") + patterns)
}

tasks.named("check") {
    dependsOn(ktlintCheck)
}
