package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.trace.NodeId

/**
 * The `partition-heal` scenario: the leader is isolated, a write is submitted to it, the partition heals, and the
 * cluster settles.
 *
 * Values: 5 nodes; three clients with 30 operations each over `x`, `y`, `z` starting at 400 ms. The leader is isolated
 * with a minority of 2 at [PARTITION_AT] = 900 ms, a `Put("m", "minority")` is submitted to it at [SUBMIT_AT] = 950 ms,
 * and the partition heals at [HEAL_AT] = 1 600 ms. The settle period after heal is the rest of the run: the run lasts
 * 3 500 ms, that is at least 2 000 ms after heal. The leader is awaited within 800 ms. Expectations: exactly one live
 * leader, converged logs, agreeing key-value data, truncation of the minority entry, and at-most-once application.
 */
internal object PartitionHealScenario {
    /** Virtual time (ms) at which the partition is created. */
    const val PARTITION_AT = 900L

    /** Virtual time (ms) at which the write is submitted to the isolated leader. */
    const val SUBMIT_AT = 950L

    /** Virtual time (ms) at which the partition is healed. */
    const val HEAL_AT = 1_600L

    /** The scenario, named `partition-heal`. */
    val scenario: Scenario =
        scenario("partition-heal") {
            var oldLeader: NodeId? = null
            var submitId = ""
            nodes(5)
            clients(count = 3, opsPerClient = 30, keys = listOf("x", "y", "z"), startAt = 400)
            at(PARTITION_AT) { oldLeader = isolateLeaderWithMinority(2).first() }
            at(SUBMIT_AT) { submitId = submitTo(checkNotNull(oldLeader), KvOp.Put("m", "minority")) }
            at(HEAL_AT) { heal() }
            awaitLeader(within = 800)
            runFor(3_500)
            expect("exactly one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("logs converged") { v -> ScenarioChecks.logsConverged(v.nodes()) }
            expect("live nodes agree") { v -> ScenarioChecks.kvAgree(v.nodes()) }
            expect("minority-only entries truncated") { v ->
                v.nodes().all { ScenarioChecks.commandsOf(it.log, submitId).isEmpty() }
            }
            expect("retried writes applied at most once") { v -> ScenarioChecks.appliedAtMostOnce(v.nodes()) }
        }
}
