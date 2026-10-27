package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PartitionHealScenarioTest {
    @Test
    fun partitionHealConvergesAndAppliesOnce() {
        for (r in LibraryRuns.results("partition-heal")) {
            val state = r.finalState
            withClue(r.report) {
                r.expectations.all { it.passed } shouldBe true
                state.all { it.up } shouldBe true
                state.map { it.log }.distinct().size shouldBe 1
                state.map { it.commitIndex }.distinct().size shouldBe 1
                state.map { it.lastApplied }.distinct().size shouldBe 1
                state.map { it.kvData }.distinct().size shouldBe 1
                ScenarioChecks.liveLeaders(state).size shouldBe 1
                ScenarioChecks.appliedAtMostOnce(state) shouldBe true
                state.all { ScenarioChecks.commandsOf(it.log, "s1").isEmpty() } shouldBe true
            }
        }
    }
}
