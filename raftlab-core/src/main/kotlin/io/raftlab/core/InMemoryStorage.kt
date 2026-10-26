package io.raftlab.core

/**
 * [RaftStorage] that keeps everything in memory.
 *
 * The instance survives simulated crashes because the simulator keeps the reference and reuses it on restart.
 */
public class InMemoryStorage : RaftStorage {
    private var state = PersistentState(0, null)
    private val log = ArrayList<LogEntry>()
    private var compactedIndex = 0L
    private var snapshot: Snapshot? = null

    /** Returns the persisted state. */
    override fun loadState(): PersistentState = state

    /** Replaces the persisted state with [state]. */
    override fun saveState(state: PersistentState) {
        this.state = state
    }

    /** Appends [entries] after the current last index, validating contiguity before any change. */
    override fun append(entries: List<LogEntry>) {
        val base = lastIndex()
        entries.forEachIndexed { k, entry ->
            require(entry.index == base + 1 + k) {
                "non-contiguous append at position $k: expected index ${base + 1 + k}, got ${entry.index}"
            }
        }
        log.addAll(entries)
    }

    /** Removes the entries from [index] through the last index. */
    override fun truncateFrom(index: Long) {
        require(index > compactedIndex) { "truncate index $index must be above compacted index $compactedIndex" }
        if (index > lastIndex()) return
        log.subList((index - compactedIndex - 1).toInt(), log.size).clear()
    }

    /** Returns the live entries with indexes in `[from, toExclusive)`. */
    override fun entries(
        from: Long,
        toExclusive: Long,
    ): List<LogEntry> {
        require(from > compactedIndex) { "from $from must be above compacted index $compactedIndex" }
        require(from <= toExclusive && toExclusive <= lastIndex() + 1) {
            "range [$from, $toExclusive) is outside the log ending at ${lastIndex()}"
        }
        return log.subList((from - compactedIndex - 1).toInt(), (toExclusive - compactedIndex - 1).toInt()).toList()
    }

    /** Returns the index of the last entry, or the compacted index when the log is empty. */
    override fun lastIndex(): Long = compactedIndex + log.size

    /** Returns the term at [index], or null when unknown. */
    override fun termAt(index: Long): Long? {
        if (index == 0L) return 0L
        if (index > compactedIndex && index <= lastIndex()) {
            return log[(index - compactedIndex - 1).toInt()].term
        }
        val saved = snapshot
        if (saved != null && index == saved.meta.lastIndex) return saved.meta.lastTerm
        return null
    }

    /** Stores a copy of [data] together with [meta], replacing any previous snapshot. */
    override fun saveSnapshot(
        meta: SnapshotMeta,
        data: ByteArray,
    ) {
        snapshot = Snapshot(meta, data.copyOf())
    }

    /** Returns a copy of the saved snapshot, or null when none exists. */
    override fun loadSnapshot(): Snapshot? = snapshot?.let { Snapshot(it.meta, it.data.copyOf()) }

    /** Drops all entries with indexes at or below [index], which requires a saved snapshot covering it. */
    override fun compactTo(index: Long) {
        val saved = snapshot
        require(saved != null && index <= saved.meta.lastIndex) {
            "compaction to $index requires a saved snapshot covering it"
        }
        if (index <= compactedIndex) return
        val drop = minOf(index - compactedIndex, log.size.toLong()).toInt()
        log.subList(0, drop).clear()
        compactedIndex = index
    }
}
