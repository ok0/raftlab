import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

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

// PAC9: minimum line coverage (percent) of the consensus core, enforced by koverVerify (part of check).
// Override for experiments with -Praftlab.coverage.coreMinLine=<percent>.
val coreMinLineCoverage =
    providers
        .gradleProperty("raftlab.coverage.coreMinLine")
        .map { it.toInt() }
        .orElse(85)

kover {
    reports {
        total {
            verify {
                onCheck.set(true)
                rule("raftlab-core line coverage") {
                    bound {
                        minValue.set(coreMinLineCoverage)
                        coverageUnits.set(CoverageUnit.LINE)
                        aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
                    }
                }
            }
        }
    }
}
