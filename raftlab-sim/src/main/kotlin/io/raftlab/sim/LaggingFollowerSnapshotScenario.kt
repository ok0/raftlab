package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId
import io.raftlab.trace.SnapshotInstalled

/**
 * The `lagging-follower-snapshot` scenario: three nodes with a snapshot threshold of [THRESHOLD] entries; one follower
 * is isolated at [ISOLATE_AT] while writes continue, so the leader compacts past its log. The follower is healed at
 * [HEAL_AT] and must install a snapshot. Then the leader is cut off from one follower at [QUORUM_AT], and writes are
 * acknowledged by the leader with the healed follower in the quorum from [QUORUM_FROM] to [QUORUM_UNTIL]; the run
 * ends at [END] after the final heal at [HEAL2_AT].
 *
 * Expectations: the follower was behind the leader's snapshot at heal, it installed a snapshot, one live leader, all
 * acknowledged writes on every node, nodes agree and are compacted, and writes with the lagging follower in the quorum
 * were acknowledged.
 */
internal object LaggingFollowerSnapshotScenario {
    /** Name of the scenario. */
    const val NAME = "lagging-follower-snapshot"

    /** Snapshot threshold of every node, in applied entries. */
    const val THRESHOLD = 10

    /** Virtual time in ms at which one follower is isolated. */
    const val ISOLATE_AT = 1_000L

    /** Virtual time in ms from which writes are submitted while the follower is isolated. */
    const val WRITE_FROM = 1_100L

    /** Virtual time in ms until which writes are submitted while the follower is isolated. */
    const val WRITE_UNTIL = 1_900L

    /** Virtual time in ms at which the isolated follower is healed. */
    const val HEAL_AT = 2_000L

    /** Virtual time in ms at which the leader is cut off from one other follower. */
    const val QUORUM_AT = 3_500L

    /** Virtual time in ms from which writes are submitted with the lagging follower in the quorum. */
    const val QUORUM_FROM = 3_600L

    /** Virtual time in ms until which writes are submitted with the lagging follower in the quorum. */
    const val QUORUM_UNTIL = 3_800L

    /** Virtual time in ms at which all partitions are healed again. */
    const val HEAL2_AT = 4_200L

    /** Virtual time in ms at which the run ends. */
    const val END = 6_000L

    /** The scenario, named [NAME]. */
    val scenario: Scenario =
        scenario(NAME) {
            var lagging: NodeId? = null
            var behind = false
            var other: NodeId? = null
            var quorumIds: List<String> = emptyList()
            nodes(3)
            snapshotThreshold(THRESHOLD)
            every(50, 600, 800) { leader()?.let { submitTo(it.id, KvOp.Put("p$now", "v$now")) } }
            at(ISOLATE_AT) {
                val l = leader() ?: return@at
                val f = nodeIds.first { it != l.id }
                lagging = f
                partition(setOf(f))
            }
            every(20, WRITE_FROM, WRITE_UNTIL) { leader()?.let { submitTo(it.id, KvOp.Put("w$now", "v$now")) } }
            at(HEAL_AT) {
                val f = lagging
                val l = leader()
                behind = f != null && l != null && node(f).lastIndex < l.snapshotIndex
                heal()
            }
            at(QUORUM_AT) {
                val l = leader() ?: return@at
                val f = lagging ?: return@at
                val o = nodeIds.first { it != l.id && it != f }
                other = o
                partition(setOf(o))
            }
            every(20, QUORUM_FROM, QUORUM_UNTIL) {
                leader()?.let { quorumIds = quorumIds + submitTo(it.id, KvOp.Put("q$now", "v$now")) }
            }
            at(HEAL2_AT) { heal() }
            runFor(END)
            expect("follower behind leader snapshot at heal") { _ -> behind }
            expect("follower installed snapshot") { v ->
                v.trace.filterIsInstance<SnapshotInstalled>().any { it.node == lagging }
            }
            expect("exactly one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("acknowledged writes on every node") { v ->
                val acked =
                    v.history.entries
                        .filter { it.result is KvResult.Ok }
                        .map { it.op }
                        .filterIsInstance<KvOp.Put>()
                acked.size > THRESHOLD && v.nodes().all { n -> acked.all { n.kvData[it.key] == it.value } }
            }
            expect("nodes agree and are compacted") { v ->
                val ns = v.nodes()
                ns.all { it.up && it.snapshotIndex >= 1 } &&
                    ScenarioChecks.kvAgree(ns) &&
                    ns.all { it.commitIndex == ns[0].commitIndex && it.lastApplied == ns[0].lastApplied }
            }
            expect("writes with lagging follower in quorum acknowledged") { v ->
                other != null && quorumIds.isNotEmpty() &&
                    quorumIds.all { id -> v.history.ofClient(id).let { it.size == 1 && it[0].result is KvResult.Ok } }
            }
        }
}
