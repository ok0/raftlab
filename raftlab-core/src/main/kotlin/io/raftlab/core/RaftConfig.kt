package io.raftlab.core

/**
 * Tunable timing for a [RaftNode], in ticks, plus test-only [Mutation]s and the snapshot threshold.
 *
 * A node takes a snapshot once more than [snapshotThreshold] applied entries lie after its last snapshot.
 * With [preVote] enabled, a node runs a pre-vote round before it starts a real election.
 */
public data class RaftConfig(
    /** Inclusive range the election timeout is drawn from on every timer reset. */
    public val electionTimeoutTicks: IntRange = 10..20,
    /** Ticks between leader heartbeats; must be below the minimum election timeout. */
    public val heartbeatTicks: Int = 3,
    /** Maximum number of entries carried by one AppendEntries; must be at least 1. */
    public val maxEntriesPerAppend: Int = 64,
    /** Deliberately injected safety bugs, for tests and fuzzing only; empty by default. */
    public val mutations: Set<Mutation> = emptySet(),
    /** Snapshot is taken once `lastApplied - snapshotIndex` exceeds this; must be at least 1. */
    public val snapshotThreshold: Int = DEFAULT_SNAPSHOT_THRESHOLD,
    /**
     * When true (default), a node whose election timer expires first runs a pre-vote round as PRE_CANDIDATE. It starts
     * a real election only after a majority would vote for it. When false, pre-vote requests and responses are ignored
     * and the node behaves as before pre-vote existed.
     */
    public val preVote: Boolean = true,
) {
    init {
        require(!electionTimeoutTicks.isEmpty()) { "electionTimeoutTicks must not be empty: $electionTimeoutTicks" }
        require(electionTimeoutTicks.first > 0) { "electionTimeoutTicks must be positive: $electionTimeoutTicks" }
        require(heartbeatTicks > 0) { "heartbeatTicks must be positive: $heartbeatTicks" }
        require(maxEntriesPerAppend >= 1) { "maxEntriesPerAppend must be at least 1: $maxEntriesPerAppend" }
        require(heartbeatTicks < electionTimeoutTicks.first) {
            "heartbeatTicks $heartbeatTicks must be below the minimum election timeout ${electionTimeoutTicks.first}"
        }
        require(snapshotThreshold >= 1) { "snapshotThreshold must be at least 1: $snapshotThreshold" }
    }

    /** Default values of [RaftConfig]. */
    public companion object {
        /** Default [snapshotThreshold]: 1000 applied entries. */
        public const val DEFAULT_SNAPSHOT_THRESHOLD: Int = 1000
    }
}
