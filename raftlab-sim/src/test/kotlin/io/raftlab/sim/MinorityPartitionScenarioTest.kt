package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.LogAppended
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

class MinorityPartitionScenarioTest {
    @Test
    fun minorityWritesNeverCommitAndAreTruncated() {
        val partitionAt = MinorityPartitionScenario.PARTITION_AT
        val healAt = MinorityPartitionScenario.HEAL_AT
        val window = partitionAt until healAt
        for (r in LibraryRuns.results("minority-partition")) {
            withClue(r.report) {
                r.expectations.size shouldBe 6
                r.expectations.all { it.passed } shouldBe true

                val leaders = r.trace.filterIsInstance<RoleChanged>().filter { it.to == Role.LEADER }
                val oldLeader =
                    leaders.last { it.time < partitionAt }
                val oldNode = oldLeader.node
                val oldTerm = oldLeader.term
                val commits = r.trace.filterIsInstance<CommitAdvanced>()
                val lastAtPartition =
                    r.trace
                        .filterIsInstance<LogAppended>()
                        .filter { it.node == oldNode && it.time <= partitionAt }
                        .maxOf { it.index }

                commits
                    .none {
                        it.node == oldNode && it.time in window && it.commitIndex > lastAtPartition
                    }.shouldBe(true)
                leaders.any { it.node != oldNode && it.term > oldTerm && it.time in window } shouldBe true
                commits.any { it.node != oldNode && it.time in window } shouldBe true

                r.history.ofClient("s1").none { e -> e.returnedAt != null && e.returnedAt in window } shouldBe true
                r.finalState.all { ScenarioChecks.commandsOf(it.log, "s1").isEmpty() } shouldBe true
            }
        }
    }
}
