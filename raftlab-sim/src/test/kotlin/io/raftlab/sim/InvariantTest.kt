package io.raftlab.sim

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.raftlab.trace.Role
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

class InvariantTest {
    @Test
    fun electionSafetyRemembersCrashedLeaders() {
        val invariant = ElectionSafety()
        val members = setOf(n1, n2)
        invariant.check(listOf(NodeState(n1, true, Role.LEADER, 1, n1, n1, members))).shouldBeEmpty()

        val second =
            listOf(
                NodeState(n1, false, null, 1, null, n1, members),
                NodeState(n2, true, Role.LEADER, 1, n2, n2, members),
            )
        val found = invariant.check(second)
        found.size shouldBe 1
        (found[0].contains("term 1")) shouldBe true
        invariant.check(second).shouldBeEmpty()
    }

    @Test
    fun forcedTwoLeadersReported() {
        val sim = Simulation(42, SimOptions(nodeCount = 3), InvariantChecker.standard(), forcedLeaderFactory)
        sim.runUntil(100)
        sim.violations.size shouldBe 1
        val first = sim.firstViolation!!
        first.invariant shouldBe "election-safety"
        first.seed shouldBe 42L
        first.time shouldBe 10L
        (first.description.contains("seed=42")) shouldBe true
        (first.description.contains("time=10")) shouldBe true
    }

    @Test
    fun cleanRunHasNoViolations() {
        runNormalElection(5).violations.shouldBeEmpty()
    }

    @Test
    fun checkerDoesNotAlterTrace() {
        val withChecks = Simulation(9, SimOptions(), InvariantChecker.standard())
        withChecks.runUntil(2000)
        val withoutChecks = Simulation(9, SimOptions(), InvariantChecker.none())
        withoutChecks.runUntil(2000)
        TraceCodec.encode(withChecks.trace) shouldBe TraceCodec.encode(withoutChecks.trace)
    }
}
