package io.raftlab.core

/** Tunable timing for a [RaftNode], in ticks, plus test-only [Mutation]s. */
public data class RaftConfig(
    /** Inclusive range the election timeout is drawn from on every timer reset. */
    public val electionTimeoutTicks: IntRange = 10..20,
    /** Ticks between leader heartbeats; must be below the minimum election timeout. */
    public val heartbeatTicks: Int = 3,
    /** Maximum number of entries carried by one AppendEntries; must be at least 1. */
    public val maxEntriesPerAppend: Int = 64,
    /** Deliberately injected safety bugs, for tests and fuzzing only; empty by default. */
    public val mutations: Set<Mutation> = emptySet(),
) {
    init {
        require(!electionTimeoutTicks.isEmpty()) { "electionTimeoutTicks must not be empty: $electionTimeoutTicks" }
        require(electionTimeoutTicks.first > 0) { "electionTimeoutTicks must be positive: $electionTimeoutTicks" }
        require(heartbeatTicks > 0) { "heartbeatTicks must be positive: $heartbeatTicks" }
        require(maxEntriesPerAppend >= 1) { "maxEntriesPerAppend must be at least 1: $maxEntriesPerAppend" }
        require(heartbeatTicks < electionTimeoutTicks.first) {
            "heartbeatTicks $heartbeatTicks must be below the minimum election timeout ${electionTimeoutTicks.first}"
        }
    }
}
