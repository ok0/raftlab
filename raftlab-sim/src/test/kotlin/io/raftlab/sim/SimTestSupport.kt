package io.raftlab.sim

import io.raftlab.core.Effect
import io.raftlab.core.Input
import io.raftlab.core.LogEntry
import io.raftlab.core.RaftMessage
import io.raftlab.core.RandomSource
import io.raftlab.core.RequestVoteResponse
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceRecorder
import io.raftlab.trace.TraceSink

val n1 = NodeId("n1")
val n2 = NodeId("n2")
val n3 = NodeId("n3")
val n4 = NodeId("n4")
val n5 = NodeId("n5")

class ForcedLeader(
    override val id: NodeId,
    override val members: Set<NodeId>,
    private val trace: TraceSink,
) : HostedNode {
    override var role: Role = Role.FOLLOWER
    override var currentTerm: Long = 0
    override var leaderHint: NodeId? = null
    override val commitIndex: Long = 0
    override val lastApplied: Long = 0

    override fun step(input: Input): List<Effect> {
        if (input == Input.Tick && role != Role.LEADER) {
            role = Role.LEADER
            currentTerm = 1
            leaderHint = id
            val nodeId = id
            trace.emit { seq, time -> RoleChanged(seq, time, nodeId, Role.FOLLOWER, Role.LEADER, 1, "forced") }
        }
        return emptyList()
    }

    override fun log(): List<LogEntry> = emptyList()
}

internal val forcedLeaderFactory = NodeFactory { id, members, _, _, _, _, trace -> ForcedLeader(id, members, trace) }

class TickLog(
    private val clock: () -> Long,
) {
    val ticks = mutableMapOf<NodeId, MutableList<Long>>()

    internal fun factory(): NodeFactory =
        NodeFactory { id, members, _, _, _, _, _ ->
            TickHost(id, members)
        }

    private inner class TickHost(
        override val id: NodeId,
        override val members: Set<NodeId>,
    ) : HostedNode {
        override val role: Role = Role.FOLLOWER
        override val currentTerm: Long = 0
        override val leaderHint: NodeId? = null
        override val commitIndex: Long = 0
        override val lastApplied: Long = 0

        override fun step(input: Input): List<Effect> {
            if (input == Input.Tick) {
                ticks.getOrPut(id) { mutableListOf() }.add(clock())
            }
            return emptyList()
        }

        override fun log(): List<LogEntry> = emptyList()
    }
}

class RecordingRandom(
    private val delegate: RandomSource,
) : RandomSource {
    val draws = mutableListOf<Long>()

    override fun nextLong(): Long = delegate.nextLong()

    override fun nextLong(bound: Long): Long {
        val value = delegate.nextLong(bound)
        draws += value
        return value
    }
}

class FirstDraws {
    val byNode = mutableMapOf<NodeId, RecordingRandom>()

    internal fun factory(): NodeFactory =
        NodeFactory { id, members, config, storage, stateMachine, random, trace ->
            val wrapped = byNode.getOrPut(id) { RecordingRandom(random) }
            NodeFactory.RAFT.create(id, members, config, storage, stateMachine, wrapped, trace)
        }
}

fun inertMsg(
    from: NodeId,
    to: NodeId,
): RaftMessage = RequestVoteResponse(0, from, to, granted = false, preVote = true)

fun recorder(clock: () -> Long = { 0L }): TraceRecorder = TraceRecorder(clock)

internal fun replicationRun(
    seed: Long,
    options: SimOptions = SimOptions(),
    checker: InvariantChecker = InvariantChecker.standard(),
    factory: NodeFactory = NodeFactory.RAFT,
    faultScript: Boolean = false,
): Simulation {
    val sim = Simulation(seed, options, checker, factory)
    var crashed: NodeId? = null
    if (faultScript) {
        for (k in 0..28) {
            sim.at(200L + 100L * k) { it.propose("c$k".encodeToByteArray()) }
        }
        sim.at(1000) { s ->
            crashed = s.leader()?.id
            crashed?.let { s.crash(it) }
        }
        sim.at(1500) { s -> crashed?.let { s.restart(it) } }
        sim.at(2000) { it.partition(setOf(n1, n2), setOf(n3, n4, n5)) }
        sim.at(2500) { it.heal() }
        sim.runUntil(3500)
    } else {
        for (k in 0..39) {
            sim.at(300L + 40L * k) { it.propose("c$k".encodeToByteArray()) }
        }
        sim.at(1000) { s ->
            crashed = s.leader()?.id
            crashed?.let { s.crash(it) }
        }
        sim.at(1400) { s -> crashed?.let { s.restart(it) } }
        sim.runUntil(4000)
    }
    return sim
}
