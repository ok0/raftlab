package io.raftlab.sim

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

class ReplicationFaultsTest {
    @Test
    fun fiftySeedsWithFaultsHaveNoViolations() {
        val opts = SimOptions(faults = NetworkFaults(1, 10, 50, 50, true))
        var seedsWithReelection = 0
        for (seed in 1L..50L) {
            val sim = replicationRun(seed, opts, faultScript = true)
            sim.violations.shouldBeEmpty()
            (sim.nodes().maxOf { it.commitIndex } > 0) shouldBe true
            val trace = sim.trace
            val firstCommit = trace.indexOfFirst { it is CommitAdvanced }
            if (firstCommit >= 0 && trace.drop(firstCommit + 1).any { it is RoleChanged && it.to == Role.LEADER }) {
                seedsWithReelection++
            }
        }
        (seedsWithReelection > 0) shouldBe true
    }
}
