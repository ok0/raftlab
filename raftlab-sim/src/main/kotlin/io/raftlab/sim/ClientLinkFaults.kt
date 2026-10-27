package io.raftlab.sim

/**
 * Faults on the links between simulated clients and the nodes they talk to, applied to client requests and replies.
 *
 * The faults are drawn from each client's own random stream and are off by default. [dropRate] stays below 1000 so
 * that clients can still make progress through retries.
 *
 * @property dropRate Drop chance per mille (0..999) of each client request and reply.
 * @property duplicateRate Duplication chance per mille (0..1000) of each client request and reply.
 * @property maxExtraDelay Maximum extra delay in virtual ms (non-negative) added to each client request and reply.
 */
public data class ClientLinkFaults(
    public val dropRate: Int = 0,
    public val duplicateRate: Int = 0,
    public val maxExtraDelay: Long = 0,
) {
    init {
        require(dropRate in 0..999) { "dropRate must be in 0..999: $dropRate" }
        require(duplicateRate in 0..1000) { "duplicateRate must be in 0..1000: $duplicateRate" }
        require(maxExtraDelay >= 0) { "maxExtraDelay must be non-negative: $maxExtraDelay" }
    }

    /** Whether any client-link fault is configured. */
    public val enabled: Boolean get() = dropRate > 0 || duplicateRate > 0 || maxExtraDelay > 0
}
