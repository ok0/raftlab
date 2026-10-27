package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled

/**
 * The `membership-add-remove` scenario: three nodes with a snapshot threshold of [THRESHOLD] entries and writes
 * submitted to the leader every [WRITE_PERIOD] ms from [WRITE_FROM] to [WRITE_UNTIL].
 *
 * Timeline: a fourth node is added at [ADD_AT] and must reach the committed configuration `n1..n4`, installing a
 * snapshot because the leader has compacted past its log. At [REMOVE_AT] the current leader is removed; it keeps
 * replicating until the new configuration commits, steps down, and another leader is elected. Writes are polled
 * from [AFTER_FROM] to [AFTER_UNTIL] after both changes are acknowledged. The run ends at [END].
 *
 * Expectations: the add committed before the removal, the removal committed and a new leader was elected, the joined
 * node installed a snapshot and holds the same data as the others, the committed members are the remaining three,
 * the removed leader stepped down and another leader was elected, exactly one leader is live, acknowledged writes
 * were acknowledged during both change windows and after them, every acknowledged write is present on every remaining
 * node, and the remaining nodes agree on data, commit index and applied index.
 */
internal object MembershipAddRemoveScenario {
    /** Name of the scenario. */
    const val NAME = "membership-add-remove"

    /** Snapshot threshold of every node, in applied entries. */
    const val THRESHOLD = 10

    /** Virtual time in ms from which writes are submitted to the leader. */
    const val WRITE_FROM = 300L

    /** Period in ms between writes submitted to the leader. */
    const val WRITE_PERIOD = 5L

    /** Virtual time in ms until which writes are submitted to the leader. */
    const val WRITE_UNTIL = 2_800L

    /** Virtual time in ms at which a fourth node is added. */
    const val ADD_AT = 1_000L

    /** Virtual time in ms at which the current leader is removed. */
    const val REMOVE_AT = 2_000L

    /** Period in ms of the polls that record when each change is acknowledged. */
    const val POLL_PERIOD = 5L

    /** Virtual time in ms from which writes are submitted after both changes. */
    const val AFTER_FROM = 3_200L

    /** Virtual time in ms until which writes are submitted after both changes. */
    const val AFTER_UNTIL = 3_600L

    /** Virtual time in ms at which the run ends. */
    const val END = 5_000L

    private val FOUR: Set<NodeId> = setOf(NodeId("n1"), NodeId("n2"), NodeId("n3"), NodeId("n4"))

    /** The scenario, named [NAME]. */
    val scenario: Scenario =
        scenario(NAME) {
            var added: NodeId? = null
            var addDoneAt: Long? = null
            var removed: NodeId? = null
            var removeDoneAt: Long? = null
            var afterIds: List<String> = emptyList()
            nodes(3)
            snapshotThreshold(THRESHOLD)
            every(WRITE_PERIOD, WRITE_FROM, WRITE_UNTIL) {
                leader()?.let {
                    submitTo(it.id, KvOp.Put("w$now", "v$now"))
                }
            }
            at(ADD_AT) { added = addNode() }
            at(REMOVE_AT) {
                leader()?.let {
                    removed = it.id
                    removeNode(it.id)
                }
            }
            every(POLL_PERIOD, ADD_AT, END) {
                if (addDoneAt == null && added != null && ScenarioChecks.membersCommitted(nodes(), FOUR)) {
                    addDoneAt = now
                }
                val r = removed
                val newLeader = leader()?.id
                if (r != null && removeDoneAt == null && r !in nodeIds && newLeader != null && newLeader != r) {
                    removeDoneAt = now
                }
            }
            every(20, AFTER_FROM, AFTER_UNTIL) {
                leader()?.let {
                    afterIds = afterIds + submitTo(it.id, KvOp.Put("a$now", "v$now"))
                }
            }
            runFor(END)
            expect("add committed before removal") { _ ->
                addDoneAt?.let { it < REMOVE_AT } == true
            }
            expect("removal committed and new leader elected") { _ -> removeDoneAt != null }
            expect("joined node installed a snapshot") { v ->
                val a = added ?: return@expect false
                val done = addDoneAt ?: return@expect false
                v.trace.filterIsInstance<SnapshotInstalled>().any { it.node == a && it.time <= done }
            }
            expect("joined node data equals the others") { v ->
                v.nodes().any { it.id == added } && ScenarioChecks.kvAgree(v.nodes())
            }
            expect("members after changes") { v ->
                val r = removed ?: return@expect false
                r !in v.nodeIds && ScenarioChecks.membersCommitted(v.nodes(), FOUR - r)
            }
            expect("removed leader stepped down and another leader elected") { v ->
                val r = removed ?: return@expect false
                val changes = v.trace.filterIsInstance<RoleChanged>()
                changes.filter { it.node == r }.lastOrNull()?.to != Role.LEADER &&
                    changes.any { it.to == Role.LEADER && it.node != r && it.time > REMOVE_AT }
            }
            expect("exactly one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("acks during add window") { v ->
                val done = addDoneAt ?: return@expect false
                v.history.entries.any { e ->
                    e.result is KvResult.Ok && e.returnedAt?.let { it in ADD_AT..done } == true
                }
            }
            expect("acks during removal window") { v ->
                val done = removeDoneAt ?: return@expect false
                v.history.entries.any { e ->
                    e.result is KvResult.Ok && e.returnedAt?.let { it in REMOVE_AT..done } == true
                }
            }
            expect("writes after both changes acknowledged") { v ->
                afterIds.isNotEmpty() &&
                    afterIds.all { id -> v.history.ofClient(id).let { it.size == 1 && it[0].result is KvResult.Ok } }
            }
            expect("acknowledged writes on every remaining node") { v ->
                val acked =
                    v.history.entries
                        .filter { it.result is KvResult.Ok }
                        .map { it.op }
                        .filterIsInstance<KvOp.Put>()
                acked.isNotEmpty() && v.nodes().all { n -> acked.all { n.kvData[it.key] == it.value } }
            }
            expect("remaining nodes agree") { v ->
                val ns = v.nodes()
                ns.isNotEmpty() &&
                    ns.all { it.up && it.commitIndex == ns[0].commitIndex && it.lastApplied == ns[0].lastApplied } &&
                    ScenarioChecks.kvAgree(ns)
            }
        }
}
