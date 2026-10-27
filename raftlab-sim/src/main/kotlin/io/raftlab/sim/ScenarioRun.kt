package io.raftlab.sim

import io.raftlab.core.InMemoryStorage
import io.raftlab.core.RaftStorage
import io.raftlab.kv.KvOp
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceEvent

internal class ScenarioRun(
    private val spec: ScenarioSpec,
    override val seed: Long,
    faults: NetworkFaults?,
    checker: InvariantChecker,
) : SimulationView {
    internal val sim =
        Simulation(
            seed,
            SimOptions(nodeCount = spec.nodes, faults = faults ?: spec.faults),
            checker,
            NodeFactory.RAFT,
            hostKv = true,
            storageFactory = storageFor(spec.storage),
        )

    private val recorder = HistoryRecorder(sim)

    internal val clients: List<SimClient> =
        spec.clients?.let { load ->
            (1..load.count).map { i ->
                val id = "c$i"
                val r = SimStreams.client(seed, i)
                SimClient(id, ClientOps.draw(id, load, r), load, sim.nodeIds.toSet(), sim, r, recorder)
            }
        } ?: emptyList()

    internal val submitters = ArrayList<OneShotClient>()

    override val now: Long get() = sim.now

    override val nodeIds: List<NodeId> get() = sim.nodeIds

    override val trace: List<TraceEvent> get() = sim.trace

    override val history: History get() = recorder.history()

    override fun node(id: NodeId): NodeView = toView(sim.node(id))

    override fun nodes(): List<NodeView> = sim.nodes().map { toView(it) }

    override fun leader(): NodeView? = nodes().filter { it.up && it.role == Role.LEADER }.maxByOrNull { it.term }

    override fun crash(id: NodeId) {
        act("crash(${id.value})") { sim.crash(id) }
    }

    override fun restart(id: NodeId) {
        act("restart(${id.value})") { sim.restart(id) }
    }

    override fun partition(vararg groups: Set<NodeId>) {
        act("partition") { sim.partition(*groups) }
    }

    override fun heal() {
        act("heal") { sim.heal() }
    }

    override fun crashLeader(): NodeId =
        act("crashLeader") {
            val id = leader()?.id ?: error("no leader")
            sim.crash(id)
            id
        }

    override fun isolateLeaderWithMinority(k: Int): Set<NodeId> =
        act("isolateLeaderWithMinority($k)") {
            require(k >= 1 && 2 * k < nodeIds.size) { "k must satisfy k >= 1 and 2k < nodeCount: $k" }
            val leaderId = leader()?.id ?: error("no leader")
            val n = nodeIds.size
            val position = nodeIds.indexOf(leaderId)
            val group = (0 until k).map { nodeIds[(position - it + n) % n] }.toSet()
            sim.partition(group, nodeIds.filter { it !in group }.toSet())
            group
        }

    override fun submitTo(
        node: NodeId,
        op: KvOp,
    ): String =
        act("submitTo(${node.value})") {
            sim.node(node)
            val n = submitters.size + 1
            val client = OneShotClient("s$n", node, op, sim, SimStreams.submitter(seed, n), recorder)
            submitters += client
            client.start()
            client.clientId
        }

    internal fun execute(): ScenarioResult {
        var failure: String? = null
        try {
            for (t in spec.timed) {
                sim.post(t.time) { t.action(this) }
            }
            clients.forEach { it.start() }
            for (step in spec.steps) {
                when (step) {
                    is ScenarioSpec.Step.AwaitLeader -> {
                        val start = sim.now
                        val deadline = start + step.within
                        if (!sim.runUntil(deadline) { leader() != null }) {
                            error("awaitLeader(within=${step.within}) at t=$start: no leader by t=$deadline")
                        }
                    }

                    is ScenarioSpec.Step.RunFor -> {
                        sim.runFor(step.duration)
                    }
                }
            }
        } catch (e: RuntimeException) {
            failure = e.message ?: e.toString()
        }
        val finalState = nodes()
        val results =
            spec.expectations.map { e ->
                try {
                    ExpectationResult(e.name, e.check(this), null)
                } catch (x: RuntimeException) {
                    ExpectationResult(e.name, false, x.message)
                }
            }
        return ScenarioResult(
            spec.name,
            seed,
            sim.trace,
            recorder.history(),
            sim.violations,
            finalState,
            results,
            failure,
        )
    }

    private inline fun <T> act(
        label: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: RuntimeException) {
            throw IllegalStateException("$label at t=${sim.now}: ${e.message}", e)
        }

    private fun toView(s: NodeState): NodeView =
        NodeView(
            id = s.id,
            up = s.up,
            role = s.role,
            term = s.currentTerm,
            leaderHint = s.leaderHint,
            commitIndex = s.commitIndex,
            lastApplied = s.lastApplied,
            log = s.log,
            kvData = if (s.up) (sim.kvData(s.id) ?: emptyMap()) else emptyMap(),
        )

    private fun storageFor(kind: StorageKind): (NodeId) -> RaftStorage {
        if (kind is StorageKind.Custom) return kind.factory
        return { _ -> InMemoryStorage() }
    }
}
