package io.raftlab.sim

import io.raftlab.core.SplitMix64

/** Derives independent deterministic random streams from a simulation seed. Stream ids are fixed forever. */
internal object SimStreams {
    private const val CLIENT_BASE = 1_000_000L
    private const val SUBMIT_BASE = 2_000_000L

    fun mix(x: Long): Long = SplitMix64(x).nextLong()

    fun network(seed: Long): SplitMix64 = SplitMix64(seed xor mix(0L))

    fun client(
        seed: Long,
        index: Int,
    ): SplitMix64 {
        require(index >= 1) { "client index must be 1-based: $index" }
        return SplitMix64(seed xor mix(CLIENT_BASE + index))
    }

    fun submitter(
        seed: Long,
        index: Int,
    ): SplitMix64 {
        require(index >= 1) { "submitter index must be 1-based: $index" }
        return SplitMix64(seed xor mix(SUBMIT_BASE + index))
    }

    fun node(
        seed: Long,
        index: Int,
    ): SplitMix64 {
        require(index >= 1) { "node index must be 1-based: $index" }
        return SplitMix64(seed xor mix(index.toLong()))
    }
}
