package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

class NormalElectionTest {
    @Test
    fun electsSingleLeaderForTwentySeeds() {
        for (seed in 1L..20L) {
            val r = runNormalElection(seed)
            (r.leader != null) shouldBe true
            (r.electedAt!! <= 5000L) shouldBe true
            r.violations.isEmpty() shouldBe true
            r.finalState.size shouldBe 5

            val changes = r.trace.filterIsInstance<RoleChanged>()
            val leaderChanges = changes.filter { it.to == Role.LEADER }
            val leadersByTerm = leaderChanges.groupBy { it.term }
            for ((_, events) in leadersByTerm) {
                (events.map { it.node }.distinct().size <= 1) shouldBe true
            }

            val leader = r.leader
            val entry = r.finalState.first { it.id == leader }
            entry.role shouldBe Role.LEADER
            entry.leaderHint shouldBe leader
        }
    }

    @Test
    fun defaultsAreFiveNodes() {
        val r = runNormalElection(1)
        r.finalState.map { it.id } shouldBe listOf(n1, n2, n3, n4, n5)
        val started = r.trace.filterIsInstance<NodeStarted>()
        started.map { it.node } shouldBe listOf(n1, n2, n3, n4, n5)
        started.all { it.time == 0L } shouldBe true
    }
}
