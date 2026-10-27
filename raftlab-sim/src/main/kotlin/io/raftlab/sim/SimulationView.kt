package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.trace.NodeId
import io.raftlab.trace.TraceEvent

/**
 * The view a scenario action or expectation has of the running simulation.
 *
 * Actions declared with `at` and `every` may change the simulation; expectations only read it.
 */
@ScenarioDsl
public interface SimulationView {
    /** Seed from which all random streams of this run are derived. */
    public val seed: Long

    /** Current virtual time in milliseconds. */
    public val now: Long

    /** Identifiers of all nodes not removed, in index order. */
    public val nodeIds: List<NodeId>

    /** Trace events recorded so far, in sequence order. */
    public val trace: List<TraceEvent>

    /** Client operations recorded so far, in invocation order. */
    public val history: History

    /** Returns the current state of the node with the given [id]. */
    public fun node(id: NodeId): NodeView

    /** Returns the current state of every node, in index order. */
    public fun nodes(): List<NodeView>

    /**
     * Returns the live node in the LEADER role with the highest term, or null when there is none.
     *
     * On equal terms the first such node in index order wins.
     */
    public fun leader(): NodeView?

    /** Crashes the node with the given [id]; its persisted state survives for a later restart. */
    public fun crash(id: NodeId)

    /** Restarts the crashed node with the given [id]. */
    public fun restart(id: NodeId)

    /** Splits the network so that nodes can only reach nodes in their own group; see `Simulation.partition`. */
    public fun partition(vararg groups: Set<NodeId>)

    /** Removes any network partition. */
    public fun heal()

    /** Adds a node; see `Simulation.addNode`. Returns the id of the new node. */
    public fun addNode(): NodeId

    /** Queues removal of the node with the given [id]; see `Simulation.removeNode`. */
    public fun removeNode(id: NodeId)

    /**
     * Crashes the current leader and returns its id.
     *
     * Fails the run when there is no live leader.
     */
    public fun crashLeader(): NodeId

    /**
     * Isolates the current leader with `k - 1` other nodes and returns the group, leader first.
     *
     * Requires `k >= 1` and `2 * k < nodeIds.size`. The other nodes are the `k - 1` nodes that precede the leader in
     * cyclic [nodeIds] order, so that a client rotating after a timeout at the old leader reaches the majority.
     * The rest of the cluster forms the other partition group. Fails the run when there is no leader.
     */
    public fun isolateLeaderWithMinority(k: Int): Set<NodeId>

    /**
     * Starts a one-shot client that sends [op] once to [node] and returns its client id, `s1`, `s2`, and so on.
     *
     * The request is never redirected or retried. The operation stays pending in [history] unless the node answers
     * with `Ok` or `CasFailed`.
     */
    public fun submitTo(
        node: NodeId,
        op: KvOp,
    ): String
}
