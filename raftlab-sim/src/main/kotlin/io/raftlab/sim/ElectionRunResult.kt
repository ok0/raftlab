package io.raftlab.sim

import io.raftlab.trace.NodeId
import io.raftlab.trace.TraceEvent

/**
 * Result of [runNormalElection].
 *
 * @property seed Seed of the run.
 * @property leader Leader at the end of the run, or `null` when none was elected.
 * @property electedAt Virtual time at which a leader first appeared, or `null` when none did within the limit.
 * @property trace Recorded trace of the run.
 * @property violations Invariant violations found during the run.
 * @property finalState Snapshots of all nodes at the end of the run, in index order.
 */
public data class ElectionRunResult(
    /** Seed of the run. */
    public val seed: Long,
    /** Leader at the end of the run, or `null` when none was elected. */
    public val leader: NodeId?,
    /** Virtual time at which a leader first appeared, or `null` when none did within the limit. */
    public val electedAt: Long?,
    /** Recorded trace of the run. */
    public val trace: List<TraceEvent>,
    /** Invariant violations found during the run. */
    public val violations: List<Violation>,
    /** Snapshots of all nodes at the end of the run, in index order. */
    public val finalState: List<NodeState>,
)
