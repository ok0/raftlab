package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("fuzz")
internal class FuzzSuiteTest {
    companion object {
        const val BASE_SEED = 0L
        const val DEFAULT_SEEDS = 1_000L
        const val SEEDS_PROPERTY = "raftlab.fuzz.seeds"
    }

    @Test
    fun fuzzSeedBudgetPasses() {
        val count = System.getProperty(SEEDS_PROPERTY)?.toLong() ?: DEFAULT_SEEDS
        val report = FuzzRunner(BASE_SEED until BASE_SEED + count).run()
        println(report.render())
        withClue(report.render()) {
            report.seedsRun shouldBe count.toInt()
            report.failed shouldBe 0
        }
    }
}
