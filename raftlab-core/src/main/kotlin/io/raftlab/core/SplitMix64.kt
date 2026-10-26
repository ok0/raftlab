package io.raftlab.core

/** Source of deterministic pseudo-random values for the simulator. */
public interface RandomSource {
    /** Returns the next pseudo-random signed 64-bit value. */
    public fun nextLong(): Long

    /** Returns a pseudo-random value in `[0, bound)`; [bound] must be positive. */
    public fun nextLong(bound: Long): Long
}

/** SplitMix64 generator seeded with [seed]; the same seed always yields the same sequence. */
public class SplitMix64(
    seed: Long,
) : RandomSource {
    private var state = seed

    /** Returns the next pseudo-random signed 64-bit value. */
    override fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Returns a pseudo-random value in `[0, bound)` using the SplittableRandom bounded algorithm. */
    override fun nextLong(bound: Long): Long {
        require(bound > 0) { "bound must be positive: $bound" }
        val m = bound - 1
        var r = nextLong()
        if (bound and m == 0L) return r and m
        var u = r ushr 1
        while (true) {
            r = u % bound
            if (u + m - r >= 0) return r
            u = nextLong() ushr 1
        }
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX1 = -0x40a7b892e31b1a47L
        const val MIX2 = -0x6b2fb644ecceee15L
    }
}
