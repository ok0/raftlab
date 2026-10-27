package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.trace.NodeId

/**
 * The `minority-partition` scenario: the leader is isolated with a minority, a write is submitted to it, and the
 * partition heals.
 *
 * Values: 5 nodes; two clients with 30 operations each over `x`, `y`, `z` starting at 400 ms. The leader is isolated
 * with a minority of 2 at [PARTITION_AT] = 800 ms, a `Put("m", "minority")` is submitted to it at [SUBMIT_AT] = 850 ms,
 * and the partition heals at [HEAL_AT] = 1 800 ms. The leader is awaited within 800 ms, then the run lasts 3 000 ms.
 * Expectations cover the old leader's commit index and log while partitioned, the majority's higher-term commit, the
 * truncation of the minority entry after heal, and agreement of live nodes.
 */
internal object MinorityPartitionScenario {
    /** Virtual time (ms) at which the partition is created. */
    const val PARTITION_AT = 800L

    /** Virtual time (ms) at which the write is submitted to the isolated leader. */
    const val SUBMIT_AT = 850L

    /** Virtual time (ms) at which the partition is healed. */
    const val HEAL_AT = 1_800L

    /** The scenario, named `minority-partition`. */
    val scenario: Scenario =
        scenario("minority-partition") {
            var minority: Set<NodeId> = emptySet()
            var oldLeader: NodeId? = null
            var startTerm = 0L
            var startCommit = 0L
            var startLast = 0L
            var endCommit = 0L
            var endLast = 0L
            var majorityTerm = 0L
            var majorityCommit = 0L
            var submitId = ""
            nodes(5)
            clients(count = 2, opsPerClient = 30, keys = listOf("x", "y", "z"), startAt = 400)
            at(PARTITION_AT) {
                minority = isolateLeaderWithMinority(2)
                val l = node(minority.first())
                oldLeader = l.id
                startTerm = l.term
                startCommit = l.commitIndex
                startLast = l.log.lastOrNull()?.index ?: 0L
            }
            at(SUBMIT_AT) { submitId = submitTo(checkNotNull(oldLeader), KvOp.Put("m", "minority")) }
            at(HEAL_AT) {
                val l = node(checkNotNull(oldLeader))
                endCommit = l.commitIndex
                endLast = l.log.lastOrNull()?.index ?: 0L
                val majority = nodes().filter { it.id !in minority }
                majorityTerm = ScenarioChecks.liveLeaders(majority).maxOfOrNull { it.term } ?: 0L
                majorityCommit = majority.maxOf { it.commitIndex }
                heal()
            }
            awaitLeader(within = 800)
            runFor(3_000)
            expect("minority commitIndex unchanged") { endCommit == startCommit }
            expect("minority log grew") { endLast > startLast }
            expect("no minority write acknowledged while partitioned") { v ->
                v.history.ofClient(submitId).all { e -> e.returnedAt.let { it == null || it >= HEAL_AT } }
            }
            expect("majority committed at a higher term") { majorityTerm > startTerm && majorityCommit > startCommit }
            expect("minority entry truncated after heal") { v ->
                v.nodes().all { ScenarioChecks.commandsOf(it.log, submitId).isEmpty() }
            }
            expect("live nodes agree") { v -> ScenarioChecks.kvAgree(v.nodes()) }
        }
}
