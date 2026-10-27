package io.raftlab.sim

import io.raftlab.core.RaftConfig

/**
 * Fault model for the simulated network.
 *
 * Delays are in virtual milliseconds. Rates are per-mille (0..1000).
 * With [reorder] set to `false` (the default) messages on each directed link are delivered in send order.
 *
 * @property minDelay Minimum delivery delay in virtual ms; must be non-negative.
 * @property maxDelay Maximum delivery delay in virtual ms; must be at least [minDelay].
 * @property dropRate Chance in per-mille that a sent message is dropped; must be in 0..1000.
 * @property duplicateRate Chance in per-mille that a sent message is duplicated; must be in 0..1000.
 * @property reorder When `false`, messages on each link keep FIFO order; when `true`, they may overtake each other.
 */
public data class NetworkFaults(
    /** Minimum delivery delay in virtual ms. */
    public val minDelay: Long = 1,
    /** Maximum delivery delay in virtual ms. */
    public val maxDelay: Long = 5,
    /** Drop chance in per-mille (0..1000). */
    public val dropRate: Int = 0,
    /** Duplication chance in per-mille (0..1000). */
    public val duplicateRate: Int = 0,
    /** When `false`, per-link FIFO order is kept; when `true`, messages may be reordered. */
    public val reorder: Boolean = false,
) {
    init {
        require(minDelay >= 0) { "minDelay must be non-negative: $minDelay" }
        require(maxDelay >= minDelay) { "maxDelay must be at least minDelay: maxDelay=$maxDelay minDelay=$minDelay" }
        require(dropRate in 0..1000) { "dropRate must be in 0..1000: $dropRate" }
        require(duplicateRate in 0..1000) { "duplicateRate must be in 0..1000: $duplicateRate" }
    }
}

/**
 * Configuration of a [Simulation].
 *
 * @property nodeCount Number of nodes, named `n1`..`nN`; must be in 1..9.
 * @property tickMillis Virtual milliseconds between node ticks; must be positive.
 * @property faults Network fault model.
 * @property raftConfig Raft timing configuration shared by all nodes.
 */
public data class SimOptions(
    /** Number of nodes, named `n1`..`nN`; must be in 1..9. */
    public val nodeCount: Int = 5,
    /** Virtual milliseconds between node ticks; must be positive. */
    public val tickMillis: Long = 10,
    /** Network fault model. */
    public val faults: NetworkFaults = NetworkFaults(),
    /** Raft timing configuration shared by all nodes. */
    public val raftConfig: RaftConfig = RaftConfig(),
) {
    init {
        require(nodeCount in 1..9) { "nodeCount must be in 1..9 so that n1..nN sort in index order: $nodeCount" }
        require(tickMillis > 0) { "tickMillis must be positive: $tickMillis" }
    }
}
