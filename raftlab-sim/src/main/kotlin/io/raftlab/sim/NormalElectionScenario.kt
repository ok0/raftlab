package io.raftlab.sim

/**
 * The `normal-election` scenario: a fault-free five-node cluster elects one leader and keeps it for 500 ms.
 *
 * Values: 5 nodes, leader awaited within 5 000 ms, then 500 ms of virtual time. Expectations: exactly one live
 * leader, and at most one leader per term in the trace.
 */
internal object NormalElectionScenario {
    /** The scenario, named `normal-election`. */
    val scenario: Scenario =
        scenario("normal-election") {
            nodes(5)
            awaitLeader(within = 5_000)
            runFor(500)
            expect("exactly one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("at most one leader per term") { v -> ScenarioChecks.oneLeaderPerTerm(v.trace) }
        }
}
