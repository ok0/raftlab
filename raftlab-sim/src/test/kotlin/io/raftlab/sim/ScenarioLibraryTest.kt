package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

class ScenarioLibraryTest {
    private object AlwaysFails : Invariant {
        override val name: String = "AlwaysFails"

        override fun check(nodes: List<NodeState>): List<String> = listOf("forced")
    }

    @Test
    fun libraryHasEightInOrder() {
        ScenarioLibrary.all.keys.toList() shouldBe
            listOf(
                "normal-election",
                "leader-crash",
                "partition-heal",
                "minority-partition",
                "client-write",
                "fuzz-random",
                "restart-all",
                "lagging-follower-snapshot",
            )
        ScenarioLibrary.all.forEach { (key, scenario) -> scenario.name shouldBe key }
    }

    @Test
    fun everyScenarioPassesTwentySeeds() {
        for (name in ScenarioLibrary.all.keys) {
            for (r in LibraryRuns.results(name)) {
                withClue(r.report) {
                    r.passed shouldBe true
                    r.violations shouldBe emptyList()
                }
            }
        }
    }

    @Test
    fun normalElectionOneLeaderPerTerm() {
        for (r in LibraryRuns.results("normal-election")) {
            withClue(r.report) {
                ScenarioChecks.oneLeaderPerTerm(r.trace) shouldBe true
                ScenarioChecks.liveLeaders(r.finalState).size shouldBe 1
                r.finalState.size shouldBe 5
            }
        }
    }

    @Test
    fun sameSeedSameBytesDifferentSeedDifferent() {
        for (scenario in ScenarioLibrary.all.values) {
            val first = scenario.run(1)
            val second = scenario.run(1)
            val other = scenario.run(2)
            withClue(scenario.name) {
                TraceCodec.encode(first.trace) shouldBe TraceCodec.encode(second.trace)
                first.history shouldBe second.history
                first.finalState shouldBe second.finalState
                (TraceCodec.encode(first.trace) != TraceCodec.encode(other.trace)) shouldBe true
            }
        }
    }

    @Test
    fun failureReportNamesScenarioAndSeed() {
        val report =
            checkNotNull(ScenarioLibrary.all["leader-crash"])
                .prepare(3, checker = InvariantChecker(listOf(AlwaysFails)))
                .execute()
                .report
        (report.contains("leader-crash") && report.contains("seed 3")) shouldBe true
    }
}
