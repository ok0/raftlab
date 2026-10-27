package io.raftlab.sim

import io.raftlab.core.Effect
import io.raftlab.core.Input
import io.raftlab.core.RaftConfig
import io.raftlab.core.RaftNode
import io.raftlab.core.RaftStorage
import io.raftlab.core.RandomSource
import io.raftlab.core.StateMachine
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceSink

/** A node as hosted by the simulator. Test doubles implement this to replace [RaftNode]. */
internal interface HostedNode {
    val id: NodeId
    val role: Role
    val currentTerm: Long
    val leaderHint: NodeId?
    val members: Set<NodeId>

    fun step(input: Input): List<Effect>
}

/** Adapts a real [RaftNode] to [HostedNode]. */
internal class RaftNodeHost(
    private val node: RaftNode,
) : HostedNode {
    override val id: NodeId get() = node.id
    override val role: Role get() = node.role
    override val currentTerm: Long get() = node.currentTerm
    override val leaderHint: NodeId? get() = node.leaderHint
    override val members: Set<NodeId> get() = node.members

    override fun step(input: Input): List<Effect> = node.step(input)
}

/** State machine that ignores all commands. */
internal object NoopStateMachine : StateMachine {
    override fun apply(
        index: Long,
        command: ByteArray,
    ): ByteArray = ByteArray(0)

    override fun snapshot(): ByteArray = ByteArray(0)

    override fun restore(data: ByteArray) {}
}

/** Creates the [HostedNode] for a node id; the seam used for real nodes and test doubles. */
internal fun interface NodeFactory {
    fun create(
        id: NodeId,
        members: Set<NodeId>,
        config: RaftConfig,
        storage: RaftStorage,
        random: RandomSource,
        trace: TraceSink,
    ): HostedNode

    companion object {
        val RAFT: NodeFactory =
            NodeFactory { id, members, config, storage, random, trace ->
                RaftNodeHost(RaftNode(id, members, config, storage, NoopStateMachine, random, trace))
            }
    }
}
