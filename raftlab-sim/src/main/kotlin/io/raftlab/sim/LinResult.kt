package io.raftlab.sim

/**
 * Verdict of a linearizability check of a [History].
 */
public sealed interface LinResult {
    /**
     * Whether this verdict is accepted. [Unknown] counts as a failure unless [allowUnknown] is true.
     *
     * @param allowUnknown True to accept an [Unknown] verdict.
     * @return True when the verdict is [Linearizable], or when it is [Unknown] and [allowUnknown] is true.
     */
    public fun accepted(allowUnknown: Boolean = false): Boolean =
        when (this) {
            is Linearizable -> true
            is Unknown -> allowUnknown
            is NotLinearizable -> false
        }

    /**
     * Every key of the history has a linearization.
     *
     * @property explored Total number of search states explored over all keys.
     */
    public data class Linearizable(
        /** Total number of search states explored over all keys. */
        public val explored: Long,
    ) : LinResult

    /**
     * A key of the history has no linearization.
     *
     * @property key The key on which the search failed.
     * @property operations All entries of [key] in canonical order.
     * @property prefix The longest linearizable prefix the search found, in linearization order.
     * @property failedAt The complete operation the search could not place, or null when there is none.
     * @property explored Number of search states explored on [key].
     */
    public data class NotLinearizable(
        /** The key on which the search failed. */
        public val key: String,
        /** All entries of [key] in canonical order. */
        public val operations: List<HistoryEntry>,
        /** The longest linearizable prefix the search found, in linearization order. */
        public val prefix: List<HistoryEntry>,
        /** The complete operation the search could not place, or null when there is none. */
        public val failedAt: HistoryEntry?,
        /** Number of search states explored on [key]. */
        public val explored: Long,
    ) : LinResult

    /**
     * The search on a key used up its state budget before reaching a verdict.
     *
     * @property key The key whose search ran out of budget.
     * @property budget The number of states the search was allowed to explore on [key].
     * @property explored Number of search states explored on [key].
     */
    public data class Unknown(
        /** The key whose search ran out of budget. */
        public val key: String,
        /** The number of states the search was allowed to explore on [key]. */
        public val budget: Long,
        /** Number of search states explored on [key]. */
        public val explored: Long,
    ) : LinResult
}
