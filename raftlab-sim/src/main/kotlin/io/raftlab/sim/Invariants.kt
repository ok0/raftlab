package io.raftlab.sim

import io.raftlab.core.LogEntry
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import java.util.TreeMap
import java.util.TreeSet

/**
 * Immutable read-only snapshot of one simulated node, as seen by [Invariant] checks.
 *
 * @property id Node identifier.
 * @property up Whether the node is currently running (not crashed).
 * @property role Current role; `null` when the node is crashed.
 * @property currentTerm Current term (persisted term when crashed).
 * @property leaderHint Believed leader; `null` when the node is crashed.
 * @property votedFor Persisted vote for the current term, if any.
 * @property members Cluster membership known to the node.
 * @property log Live log; persisted log when crashed.
 * @property commitIndex Commit index; `0` when crashed.
 * @property lastApplied Last applied index; `0` when crashed.
 * @property incarnation Number of crashes so far; a change while up means the node restarted with fresh volatile state.
 */
public data class NodeState(
    /** Node identifier. */
    public val id: NodeId,
    /** Whether the node is currently running (not crashed). */
    public val up: Boolean,
    /** Current role; `null` when the node is crashed. */
    public val role: Role?,
    /** Current term (persisted term when crashed). */
    public val currentTerm: Long,
    /** Believed leader; `null` when the node is crashed. */
    public val leaderHint: NodeId?,
    /** Persisted vote for the current term, if any. */
    public val votedFor: NodeId?,
    /** Cluster membership known to the node. */
    public val members: Set<NodeId>,
    /** Live log; persisted log when crashed. */
    public val log: List<LogEntry> = emptyList(),
    /** Commit index; `0` when crashed. */
    public val commitIndex: Long = 0,
    /** Last applied index; `0` when crashed. */
    public val lastApplied: Long = 0,
    /** Number of crashes so far; a change while up means the node restarted with fresh volatile state. */
    public val incarnation: Int = 0,
)

/**
 * A safety violation detected during a simulation run.
 *
 * @property invariant Name of the violated [Invariant].
 * @property seed Seed of the simulation in which the violation occurred.
 * @property time Virtual time at which the violation was detected.
 * @property detail Human-readable description of the violation.
 */
public data class Violation(
    /** Name of the violated [Invariant]. */
    public val invariant: String,
    /** Seed of the simulation in which the violation occurred. */
    public val seed: Long,
    /** Virtual time at which the violation was detected. */
    public val time: Long,
    /** Human-readable description of the violation. */
    public val detail: String,
) {
    /** Full human-readable description including invariant name, seed, time and detail. */
    public val description: String
        get() = "invariant '$invariant' violated (seed=$seed, time=$time): $detail"

    /** Returns [description]. */
    override fun toString(): String = description
}

/**
 * A safety property checked against the node snapshots after every simulation step.
 *
 * Implementations return details only for violations found for the first time, so they may keep state.
 * Use one instance per simulation.
 */
public interface Invariant {
    /** Stable name of this invariant, used in [Violation.invariant]. */
    public val name: String

    /**
     * Checks the given node snapshots.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of newly found violations; empty when none.
     */
    public fun check(nodes: List<NodeState>): List<String>
}

/**
 * Checks that at most one leader is ever elected per term.
 *
 * Leaders that have since crashed are still remembered, so a term with two leaders is reported
 * even when one of them is no longer running. Each term is reported at most once.
 */
public class ElectionSafety : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "election-safety"

    private val leaders = TreeMap<Long, NodeId>()
    private val reported = TreeSet<Long>()

    /**
     * Checks the given node snapshots for two leaders in the same term.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of terms that newly have two distinct leaders.
     */
    public override fun check(nodes: List<NodeState>): List<String> {
        val out = mutableListOf<String>()
        for (node in nodes) {
            if (!node.up || node.role != Role.LEADER) continue
            val term = node.currentTerm
            val prev = leaders[term]
            if (prev == null) {
                leaders[term] = node.id
                continue
            }
            if (prev != node.id && term !in reported) {
                reported.add(term)
                out.add("term $term has two leaders: ${prev.value} and ${node.id.value}")
            }
        }
        return out
    }
}

/**
 * Runs a fixed set of [Invariant]s and turns their findings into [Violation]s.
 *
 * Each instance belongs to one simulation.
 *
 * @property invariants Invariants checked on every call to [check], in order.
 */
public class InvariantChecker(
    /** Invariants checked on every call to [check], in order. */
    private val invariants: List<Invariant>,
) {
    /** Names of the checked invariants, in order. */
    public val invariantNames: List<String> get() = invariants.map { it.name }

    /**
     * Runs all invariants against the given snapshots.
     *
     * @param seed Seed of the running simulation.
     * @param time Virtual time of the check.
     * @param nodes Snapshots of all nodes in index order.
     * @return Violations found by this check.
     */
    public fun check(
        seed: Long,
        time: Long,
        nodes: List<NodeState>,
    ): List<Violation> =
        invariants.flatMap { inv ->
            inv.check(nodes).map { Violation(inv.name, seed, time, it) }
        }

    /** Factory methods for common checker configurations. */
    public companion object {
        /**
         * Returns a checker with the standard invariants: election safety, log matching, leader completeness,
         * state machine safety and commit monotonicity.
         */
        public fun standard(): InvariantChecker =
            InvariantChecker(
                listOf(
                    ElectionSafety(),
                    LogMatching(),
                    LeaderCompleteness(),
                    StateMachineSafety(),
                    CommitMonotonicity(),
                ),
            )

        /** Returns a checker that checks nothing. */
        public fun none(): InvariantChecker = InvariantChecker(emptyList())
    }
}
