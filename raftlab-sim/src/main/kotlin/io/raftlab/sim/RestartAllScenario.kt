package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted

/**
 * The `restart-all` scenario: three nodes with durable storage opened through a [DurableStorageProvider]; two clients
 * each run 8 operations over the keys `a`, `b` and `c`, starting at 300 ms.
 *
 * All nodes crash at [CRASH_AT] and restart at [RESTART_AT]; restarted nodes reopen their storage from disk. The
 * replay check runs at [CHECK_AT]. Post-restart writes are submitted to the leader at [POST_AT] and [POST_AT] + 50.
 * Expectations: writes acknowledged and client load completed before the crash, every node crashed and restarted,
 * log and data replayed from disk, one leader, acknowledged writes survive, nodes converged, and post-restart writes
 * acknowledged.
 *
 * Three nodes keep the number of fsyncs low.
 */
internal object RestartAllScenario {
    /** Name of the scenario. */
    const val NAME = "restart-all"

    /** Virtual time in ms at which all nodes crash. */
    const val CRASH_AT = 2_000L

    /** Virtual time in ms at which all nodes restart. */
    const val RESTART_AT = 2_200L

    /** Virtual time in ms at which the replay check runs. */
    const val CHECK_AT = 3_200L

    /** Virtual time in ms of the first post-restart write. */
    const val POST_AT = 3_300L

    /** Duration in ms of the final run phase. */
    const val RUN = 4_500L

    /** Name of the expectation that checks replay from disk. */
    const val REPLAYED = "log and data replayed from disk"

    /** The scenario with no registered provider; it is resolved when the run starts. */
    val scenario: Scenario = build(null)

    /**
     * Builds the scenario with the given provider, or with the discovered provider when [provider] is null.
     *
     * @param provider Opens each node's storage, or null to discover a registered provider when the run starts.
     * @return The `restart-all` scenario using [provider].
     */
    fun build(provider: DurableStorageProvider?): Scenario =
        scenario(NAME) {
            var before: History? = null
            var preLeader: NodeView? = null
            var replayed = false
            var postIds: List<String> = emptyList()
            nodes(3)
            storage(StorageKind.Durable(provider))
            clients(count = 2, opsPerClient = 8, keys = listOf("a", "b", "c"), startAt = 300)
            at(CRASH_AT) {
                before = history
                preLeader = leader()
                nodeIds.forEach { crash(it) }
            }
            at(RESTART_AT) { nodeIds.forEach { restart(it) } }
            at(CHECK_AT) { replayed = replayedFromDisk(preLeader, nodes()) }
            at(POST_AT) { postIds = postIds + submitTo(leaderId(), KvOp.Put("a", "after-restart")) }
            at(POST_AT + 50) { postIds = postIds + submitTo(leaderId(), KvOp.Delete("b")) }
            awaitLeader(within = 1_000)
            runFor(RUN)
            expect("write acknowledged before restart") { _ ->
                before?.entries?.any { ScenarioChecks.isWrite(it.op) && it.result is KvResult.Ok } == true
            }
            expect("client load completed before restart") { _ ->
                before?.let { it.entries.size == 16 && it.pending().isEmpty() } == true
            }
            expect("every node crashed and restarted") { v ->
                val crashed =
                    v.trace
                        .filterIsInstance<NodeCrashed>()
                        .mapNotNull { it.node }
                        .toSet()
                val restarted =
                    v.trace
                        .filterIsInstance<NodeRestarted>()
                        .mapNotNull { it.node }
                        .toSet()
                crashed == v.nodeIds.toSet() && restarted == v.nodeIds.toSet()
            }
            expect(REPLAYED) { _ -> replayed }
            expect("one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("acknowledged writes survive") { v ->
                ScenarioChecks.acknowledgedWritesSurvive(v.history, v.nodes())
            }
            expect("nodes converged") { v ->
                ScenarioChecks.logsConverged(v.nodes()) && ScenarioChecks.kvAgree(v.nodes())
            }
            expect("post-restart writes acknowledged") { v ->
                postIds.size == 2 && postIds.all { id -> ackedOnce(v.history.ofClient(id)) }
            }
        }

    private fun ackedOnce(entries: List<HistoryEntry>): Boolean = entries.size == 1 && entries[0].result is KvResult.Ok

    private fun SimulationView.leaderId(): NodeId = leader()?.id ?: error("no leader at t=$now")

    /**
     * Checks that the committed prefix of the leader at crash time and its key-value data are present on every node
     * after restart.
     *
     * @param pre Leader snapshot taken at the crash, or null when no leader existed.
     * @param nodes Nodes at the time of the check.
     * @return `true` when every node is up and holds the committed log prefix and the same key-value data.
     */
    fun replayedFromDisk(
        pre: NodeView?,
        nodes: List<NodeView>,
    ): Boolean {
        val leader = pre ?: return false
        val committed = leader.log.filter { it.index <= leader.commitIndex }
        if (committed.isEmpty()) return false
        return nodes.all { n ->
            n.up && n.log.size >= committed.size && n.log.subList(0, committed.size) == committed &&
                n.kvData == leader.kvData
        }
    }
}
