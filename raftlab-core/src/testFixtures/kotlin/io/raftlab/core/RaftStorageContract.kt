package io.raftlab.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

/**
 * Reusable behavioural contract for [RaftStorage] implementations.
 *
 * Subclasses supply the storage under test through [createStorage] and may override [reopen] to simulate a restart.
 */
public abstract class RaftStorageContract {
    /** Creates a fresh, empty storage instance for a single test. */
    protected abstract fun createStorage(): RaftStorage

    /** Returns a storage that sees the durable state of [storage] as after a restart; defaults to the same instance. */
    protected open fun reopen(storage: RaftStorage): RaftStorage = storage

    private fun e(
        i: Long,
        t: Long,
    ): LogEntry = LogEntry(i, t, EntryPayload.Command(byteArrayOf(i.toByte())))

    private fun meta(
        i: Long,
        t: Long,
    ): SnapshotMeta = SnapshotMeta(i, t, setOf(NodeId("n1"), NodeId("n2")))

    private fun assertThreeEntries(storage: RaftStorage) {
        storage.lastIndex() shouldBe 3L
        storage.termAt(3) shouldBe 2L
        storage.entries(1, 4) shouldBe listOf(e(1, 1), e(2, 1), e(3, 2))
        storage.entries(2, 3) shouldBe listOf(e(2, 1))
    }

    /** A fresh storage reports empty state, log and snapshot. */
    @Test
    public fun freshStorageIsEmpty() {
        val storage = createStorage()
        storage.loadState() shouldBe PersistentState(0, null)
        storage.lastIndex() shouldBe 0L
        storage.termAt(0) shouldBe 0L
        storage.termAt(1) shouldBe null
        storage.loadSnapshot() shouldBe null
        storage.entries(1, 1) shouldBe emptyList()
    }

    /** Saved state is still present after a reopen. */
    @Test
    public fun savedStateSurvivesReopen() {
        val storage = createStorage()
        storage.saveState(PersistentState(3, NodeId("n2")))
        reopen(storage).loadState() shouldBe PersistentState(3, NodeId("n2"))
    }

    /** Contiguous appends are visible immediately and after a reopen. */
    @Test
    public fun appendsContiguousEntries() {
        val storage = createStorage()
        storage.append(listOf(e(1, 1), e(2, 1), e(3, 2)))
        assertThreeEntries(storage)
        assertThreeEntries(reopen(storage))
    }

    /** Appending an empty list changes nothing. */
    @Test
    public fun appendEmptyIsNoOp() {
        val storage = createStorage()
        storage.append(emptyList())
        storage.lastIndex() shouldBe 0L
    }

    /** Appends whose indexes are not contiguous with the current last index are rejected. */
    @Test
    public fun rejectsNonContiguousAppend() {
        val storage = createStorage()
        shouldThrow<IllegalArgumentException> { storage.append(listOf(e(2, 1))) }
        shouldThrow<IllegalArgumentException> { storage.append(listOf(e(1, 1), e(3, 1))) }
        storage.append(listOf(e(1, 1)))
        shouldThrow<IllegalArgumentException> { storage.append(listOf(e(3, 1))) }
    }

    /** Truncation removes the suffix from the given index and allows appending again. */
    @Test
    public fun truncateRemovesSuffix() {
        val storage = createStorage()
        storage.append((1L..5L).map { e(it, 1) })
        storage.truncateFrom(3)
        storage.lastIndex() shouldBe 2L
        storage.termAt(3) shouldBe null
        storage.entries(1, 3) shouldBe listOf(e(1, 1), e(2, 1))
        storage.append(listOf(e(3, 3)))
        storage.termAt(3) shouldBe 3L
        storage.truncateFrom(10)
        storage.lastIndex() shouldBe 3L
    }

    /** Entry reads outside the valid range are rejected. */
    @Test
    public fun entriesRejectsOutOfRange() {
        val storage = createStorage()
        storage.append(listOf(e(1, 1), e(2, 1), e(3, 1)))
        shouldThrow<IllegalArgumentException> { storage.entries(1, 5) }
        shouldThrow<IllegalArgumentException> { storage.entries(3, 2) }
        shouldThrow<IllegalArgumentException> { storage.entries(0, 1) }
    }

    /** A saved snapshot is copied on save and returned as a copy, and survives a reopen. */
    @Test
    public fun snapshotRoundTrip() {
        val storage = createStorage()
        val before = storage.lastIndex()
        val bytes = byteArrayOf(1, 2, 3)
        storage.saveSnapshot(meta(2, 1), bytes)
        bytes[0] = 9
        storage.loadSnapshot() shouldBe Snapshot(meta(2, 1), byteArrayOf(1, 2, 3))
        reopen(storage).loadSnapshot() shouldBe Snapshot(meta(2, 1), byteArrayOf(1, 2, 3))
        storage.lastIndex() shouldBe before
    }

    /** Compaction drops the prefix covered by the snapshot while keeping the remaining entries readable. */
    @Test
    public fun compactionDropsPrefix() {
        val storage = createStorage()
        storage.append(listOf(e(1, 1), e(2, 1), e(3, 2), e(4, 2), e(5, 3)))
        storage.saveSnapshot(meta(3, 2), byteArrayOf(7))
        storage.compactTo(3)
        storage.lastIndex() shouldBe 5L
        storage.termAt(3) shouldBe 2L
        storage.termAt(2) shouldBe null
        storage.termAt(4) shouldBe 2L
        storage.entries(4, 6) shouldBe listOf(e(4, 2), e(5, 3))
        shouldThrow<IllegalArgumentException> { storage.entries(3, 4) }
        shouldThrow<IllegalArgumentException> { storage.truncateFrom(3) }
        storage.append(listOf(e(6, 3)))
    }

    /** Compacting beyond the end of the log empties it and moves the last index to the compacted index. */
    @Test
    public fun compactionBeyondLog() {
        val storage = createStorage()
        storage.append(listOf(e(1, 1), e(2, 1)))
        storage.saveSnapshot(meta(10, 4), byteArrayOf())
        storage.compactTo(10)
        storage.lastIndex() shouldBe 10L
        storage.termAt(10) shouldBe 4L
        storage.termAt(2) shouldBe null
        storage.entries(11, 11) shouldBe emptyList()
        storage.append(listOf(e(11, 4)))
    }

    /** Compaction requires a saved snapshot that covers the requested index. */
    @Test
    public fun compactRequiresSnapshot() {
        val storage = createStorage()
        storage.append(listOf(e(1, 1)))
        shouldThrow<IllegalArgumentException> { storage.compactTo(1) }
        storage.saveSnapshot(meta(1, 1), byteArrayOf())
        shouldThrow<IllegalArgumentException> { storage.compactTo(2) }
    }
}
