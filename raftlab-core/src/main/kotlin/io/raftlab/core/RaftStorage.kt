package io.raftlab.core

import io.raftlab.trace.NodeId
import kotlinx.serialization.Serializable

/** Durable Raft state that must survive restarts: the current [term] and the node's [votedFor]. */
@Serializable
public data class PersistentState(
    /** Latest term the node has seen. */
    public val term: Long,
    /** Candidate voted for in [term], or null when no vote was cast. */
    public val votedFor: NodeId?,
)

/** Metadata describing a snapshot: the last covered entry and the membership at that point. */
@Serializable
public data class SnapshotMeta(
    /** Index of the last log entry covered by the snapshot. */
    public val lastIndex: Long,
    /** Term of the last log entry covered by the snapshot. */
    public val lastTerm: Long,
    /** Cluster membership at the snapshot point. */
    public val members: Set<NodeId>,
)

/** A saved snapshot. Equality and string form cover the snapshot bytes by content. */
public class Snapshot(
    /** Metadata describing the snapshot. */
    public val meta: SnapshotMeta,
    /** Opaque state machine bytes captured by the snapshot. */
    public val data: ByteArray,
) {
    /** Compares snapshots by [meta] and by [data] content. */
    override fun equals(other: Any?): Boolean =
        other is Snapshot && meta == other.meta && data.contentEquals(other.data)

    /** Hashes snapshots by [meta] and by [data] content. */
    override fun hashCode(): Int = 31 * meta.hashCode() + data.contentHashCode()

    /** Renders the metadata and the [data] content. */
    override fun toString(): String = "Snapshot(meta=$meta, data=${data.contentToString()})"
}

/**
 * Durable storage for one Raft node.
 *
 * Contract: indexes start at 1 and index 0 denotes the empty prefix. Every write is durable when the call returns.
 * Misuse of any operation throws [IllegalArgumentException] via `require`.
 * Entries at or below the compacted index are gone; the compacted index is the index passed to the last effective
 * [compactTo], and it equals [lastIndex] when all entries are compacted.
 */
public interface RaftStorage {
    /** Returns the persisted state; a fresh storage returns `PersistentState(0, null)`. */
    public fun loadState(): PersistentState

    /** Replaces the persisted state with [state]. */
    public fun saveState(state: PersistentState)

    /**
     * Appends [entries] after the current last index. An empty list is a no-op.
     * Requires `entries[k].index == lastIndex() + 1 + k` for every position `k`.
     */
    public fun append(entries: List<LogEntry>)

    /**
     * Removes the entries from [index] through [lastIndex].
     * Requires [index] to be greater than the compacted index; a no-op when `index > lastIndex()`.
     */
    public fun truncateFrom(index: Long)

    /**
     * Returns the live entries with indexes in `[from, toExclusive)`.
     * Requires `from` to be greater than the compacted index and `from <= toExclusive <= lastIndex() + 1`.
     */
    public fun entries(
        from: Long,
        toExclusive: Long,
    ): List<LogEntry>

    /**
     * Returns the index of the last entry, 0 when the log is empty,
     * or the compacted index when all entries are compacted.
     */
    public fun lastIndex(): Long

    /**
     * Returns the term at [index], or null when unknown.
     * Index 0 yields 0, a live entry yields its term, and the snapshot's `lastIndex` yields the snapshot's `lastTerm`.
     */
    public fun termAt(index: Long): Long?

    /** Stores a snapshot with [meta] and [data], replacing any previous one. The log is not modified. */
    public fun saveSnapshot(
        meta: SnapshotMeta,
        data: ByteArray,
    )

    /** Returns the saved snapshot, or null when none exists. */
    public fun loadSnapshot(): Snapshot?

    /**
     * Drops all entries with indexes at or below [index].
     * Requires a saved snapshot with `index <= meta.lastIndex`. [index] may exceed [lastIndex]; in that case the log
     * becomes empty and `lastIndex()` equals [index]. A no-op when [index] is at or below the compacted index.
     */
    public fun compactTo(index: Long)
}
