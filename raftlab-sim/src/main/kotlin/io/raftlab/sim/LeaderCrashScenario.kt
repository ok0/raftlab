package io.raftlab.sim

import io.raftlab.trace.NodeId

/**
 * The `leader-crash` scenario: the leader crashes under client load, a new leader is elected, and the crashed node
 * restarts.
 *
 * Values: 5 nodes; two clients with 40 operations each over `x`, `y`, `z` starting at 500 ms. The leader crashes at
 * [CRASH_AT] = 1 000 ms and the same node restarts at [RESTART_AT] = 2 000 ms. The leader is awaited within 1 000 ms,
 * then the run lasts 3 500 ms. Expectations: a leader exists at a higher term than the crashed one, acknowledged
 * writes survive, all nodes are live, and live nodes agree on the key-value data.
 */
internal object LeaderCrashScenario {
    /** Virtual time (ms) at which the leader is crashed. */
    const val CRASH_AT = 1_000L

    /** Virtual time (ms) at which the crashed node is restarted. */
    const val RESTART_AT = 2_000L

    /** The scenario, named `leader-crash`. */
    val scenario: Scenario =
        scenario("leader-crash") {
            var crashed: NodeId? = null
            var crashedTerm = 0L
            nodes(5)
            clients(count = 2, opsPerClient = 40, keys = listOf("x", "y", "z"), startAt = 500)
            at(CRASH_AT) {
                crashedTerm = leader()?.term ?: 0L
                crashed = crashLeader()
            }
            at(RESTART_AT) { crashed?.let { restart(it) } }
            awaitLeader(within = 1_000)
            runFor(3_500)
            expect("new leader at higher term") { v -> v.leader()?.let { it.term > crashedTerm } == true }
            expect("acknowledged writes survive") { v ->
                ScenarioChecks.acknowledgedWritesSurvive(v.history, v.nodes())
            }
            expect("all nodes live") { v -> v.nodes().all { it.up } }
            expect("live nodes agree") { v -> ScenarioChecks.kvAgree(v.nodes()) }
        }
}
