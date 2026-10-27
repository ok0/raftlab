package io.raftlab.sim

import io.raftlab.core.RaftConfig
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged

/**
 * The `leadership-transfer` scenario: five nodes and three clients issuing operations from [CLIENT_START]. Writes are
 * submitted to the leader every [WRITE_PERIOD] ms from [WRITE_FROM] to [WRITE_UNTIL].
 *
 * Timeline: from [TRANSFER_FROM] to [TRANSFER_UNTIL] the first poll that finds a leader asks it to transfer leadership
 * to the next node in the configuration (`Simulation.transferLeadership`), and the time of that request is recorded.
 * Writes are polled from [AFTER_FROM] to [AFTER_UNTIL] after the transfer. The run ends at [END].
 *
 * Expectations: the target became leader at the next term within [BOUND_MS] ms of the request, the transfer was traced
 * with the old leader and the target, the old leader ended as a follower, exactly one leader is live, writes submitted
 * after the transfer were acknowledged, every acknowledged unique-key write is present on every node, and the nodes agree on data,
 * commit index and applied index.
 */
internal object LeadershipTransferScenario {
    /** Name of the scenario. */
    const val NAME = "leadership-transfer"

    /** Number of simulated clients. */
    const val CLIENTS = 3

    /** Number of operations issued by each client. */
    const val OPS = 40

    /** Virtual time in ms at which the clients start. */
    const val CLIENT_START = 300L

    /** Period in ms between writes submitted to the leader. */
    const val WRITE_PERIOD = 10L

    /** Virtual time in ms from which writes are submitted to the leader. */
    const val WRITE_FROM = 300L

    /** Virtual time in ms until which writes are submitted to the leader. */
    const val WRITE_UNTIL = 2_400L

    /** Virtual time in ms from which the transfer is requested. */
    const val TRANSFER_FROM = 1_000L

    /** Virtual time in ms until which the transfer is requested. */
    const val TRANSFER_UNTIL = 1_500L

    /** Period in ms of the polls that look for a leader and record the transfer request. */
    const val POLL_PERIOD = 5L

    /** Virtual time in ms from which writes are submitted after the transfer. */
    const val AFTER_FROM = 2_000L

    /** Virtual time in ms until which writes are submitted after the transfer. */
    const val AFTER_UNTIL = 2_400L

    /** Virtual time in ms at which the run ends. */
    const val END = 3_500L

    /** Maximum delay in ms between the transfer request and the target becoming leader. */
    val BOUND_MS: Long = 2L * RaftConfig().electionTimeoutTicks.last * SimOptions().tickMillis

    private val CLIENT_KEYS = setOf("a", "b", "c")

    /** The scenario, named [NAME]. */
    val scenario: Scenario =
        scenario(NAME) {
            var oldLeader: NodeId? = null
            var oldTerm = 0L
            var target: NodeId? = null
            var requestedAt: Long? = null
            var afterIds: List<String> = emptyList()
            nodes(5)
            clients(count = CLIENTS, opsPerClient = OPS, keys = listOf("a", "b", "c"), startAt = CLIENT_START)
            every(WRITE_PERIOD, WRITE_FROM, WRITE_UNTIL) {
                leader()?.let {
                    submitTo(it.id, KvOp.Put("w$now", "v$now"))
                }
            }
            every(POLL_PERIOD, TRANSFER_FROM, TRANSFER_UNTIL) {
                if (requestedAt == null) {
                    leader()?.let { l ->
                        val ids = nodeIds
                        val t = ids[(ids.indexOf(l.id) + 1) % ids.size]
                        oldLeader = l.id
                        oldTerm = l.term
                        target = t
                        requestedAt = now
                        transferLeadership(t)
                    }
                }
            }
            every(20, AFTER_FROM, AFTER_UNTIL) {
                leader()?.let {
                    afterIds = afterIds + submitTo(it.id, KvOp.Put("a$now", "v$now"))
                }
            }
            runFor(END)
            expect("target leader within bound at next term") { v ->
                val t = target ?: return@expect false
                val req = requestedAt ?: return@expect false
                v.trace.filterIsInstance<RoleChanged>().any {
                    it.node == t && it.to == Role.LEADER && it.term == oldTerm + 1 &&
                        it.time in req..req + BOUND_MS
                }
            }
            expect("transfer traced") { v ->
                val old = oldLeader ?: return@expect false
                val t = target ?: return@expect false
                v.trace.filterIsInstance<LeadershipTransfer>().any { it.node == old && it.target == t }
            }
            expect("old leader ended follower") { v ->
                val old = oldLeader ?: return@expect false
                v.node(old).role == Role.FOLLOWER
            }
            expect("exactly one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("writes after transfer acknowledged") { v ->
                afterIds.isNotEmpty() &&
                    afterIds.all { id -> v.history.ofClient(id).let { it.size == 1 && it[0].result is KvResult.Ok } }
            }
            expect("acknowledged writes on every node") { v ->
                val acked =
                    v.history.entries
                        .filter { it.result is KvResult.Ok }
                        .map { it.op }
                        .filterIsInstance<KvOp.Put>()
                        .filter { it.key !in CLIENT_KEYS }
                acked.isNotEmpty() && v.nodes().all { n -> acked.all { n.kvData[it.key] == it.value } }
            }
            expect("nodes agree") { v ->
                val ns = v.nodes()
                ns.isNotEmpty() &&
                    ns.all { it.up && it.commitIndex == ns[0].commitIndex && it.lastApplied == ns[0].lastApplied } &&
                    ScenarioChecks.kvAgree(ns)
            }
        }
}
