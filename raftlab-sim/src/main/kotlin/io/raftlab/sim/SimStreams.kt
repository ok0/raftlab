package io.raftlab.sim

import io.raftlab.core.SplitMix64

/** Derives independent deterministic random streams from a simulation seed. Stream ids are fixed forever. */
internal object SimStreams {
    fun mix(x: Long): Long = SplitMix64(x).nextLong()

    fun network(seed: Long): SplitMix64 = SplitMix64(seed xor mix(0L))

    fun node(
        seed: Long,
        index: Int,
    ): SplitMix64 {
        require(index >= 1) { "node index must be 1-based: $index" }
        return SplitMix64(seed xor mix(index.toLong()))
    }
}
