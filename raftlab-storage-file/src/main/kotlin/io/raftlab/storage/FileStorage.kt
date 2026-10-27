package io.raftlab.storage

import io.raftlab.core.LogEntry
import io.raftlab.core.PersistentState
import io.raftlab.core.RaftStorage
import io.raftlab.core.Snapshot
import io.raftlab.core.SnapshotMeta
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * [RaftStorage] that persists one node's state in a directory and reopens it from disk.
 *
 * Layout inside [dir]:
 * - `state.json`: the JSON of the persistent state, replaced atomically on every save;
 * - `log.dat`: a 12-byte header (magic and compacted index) followed by records, each a 4-byte length, a 4-byte CRC32
 *   of the payload and the UTF-8 JSON payload of one entry; all integers are big-endian;
 * - `snapshot.bin` and `snapshot.meta.json`: the raw snapshot data and the JSON of its metadata with the data length
 *   and CRC32, both replaced atomically.
 *
 * Every write is fsynced before the call returns. On open, leftover `*.tmp` files from interrupted replacements are
 * deleted, and a torn or damaged log tail, starting at the first invalid record, is discarded and truncated away.
 * A snapshot whose data does not match its metadata makes the open fail. There is no clock, no randomness and no
 * thread: the same operations on an empty directory always produce the same bytes.
 *
 * One instance must own a directory at a time; opening a second instance on the same directory while the first is in
 * use is not supported. I/O errors propagate to the caller.
 *
 * @property dir The directory that holds this storage; created when absent.
 */
public class FileStorage(
    /** The directory that holds this storage. */
    public val dir: Path,
) : RaftStorage {
    private val statePath: Path = dir.resolve(STATE_FILE)
    private val logPath: Path = dir.resolve(LOG_FILE)
    private val snapshotDataPath: Path = dir.resolve(SNAPSHOT_DATA_FILE)
    private val snapshotMetaPath: Path = dir.resolve(SNAPSHOT_META_FILE)
    private var state: PersistentState = PersistentState(0, null)
    private var compactedIndex: Long = 0L
    private val log = ArrayList<LogEntry>()
    private val offsets = ArrayList<Long>()
    private var end: Long = 0L
    private var snapshot: Snapshot? = null

    init {
        Files.createDirectories(dir)
        for (name in listOf(STATE_FILE, LOG_FILE, SNAPSHOT_DATA_FILE, SNAPSHOT_META_FILE)) {
            Files.deleteIfExists(dir.resolve(name + AtomicFiles.TMP_SUFFIX))
        }
        if (Files.exists(statePath)) {
            state = LogCodec.json.decodeFromString(PersistentState.serializer(), Files.readString(statePath))
        }
        snapshot = readSnapshot()
        if (!Files.exists(logPath)) {
            AtomicFiles.write(logPath, LogCodec.header(0))
        }
        val bytes = Files.readAllBytes(logPath)
        val scan = LogCodec.scan(bytes)
        compactedIndex = scan.compactedIndex
        log += scan.entries
        offsets += scan.offsets
        end = scan.validEnd
        if (end < bytes.size) {
            truncateLogFile(end)
        }
    }

    /** Returns the persisted state read from disk, or `PersistentState(0, null)` for a fresh directory. */
    override fun loadState(): PersistentState = state

    /** Replaces the persisted state with [state], writing it to disk durably before returning. */
    override fun saveState(state: PersistentState) {
        AtomicFiles.write(
            statePath,
            LogCodec.json.encodeToString(PersistentState.serializer(), state).encodeToByteArray(),
        )
        this.state = state
    }

    /**
     * Appends [entries] after the current last index and fsyncs the log before returning. An empty list is a no-op.
     * Requires `entries[k].index == lastIndex() + 1 + k` for every position `k`.
     */
    override fun append(entries: List<LogEntry>) {
        val base = lastIndex()
        entries.forEachIndexed { k, entry ->
            require(entry.index == base + 1 + k) {
                "non-contiguous append at position $k: expected index ${base + 1 + k}, got ${entry.index}"
            }
        }
        if (entries.isEmpty()) return
        val chunks = entries.map { LogCodec.record(it) }
        FileChannel.open(logPath, StandardOpenOption.WRITE).use { channel ->
            channel.position(end)
            AtomicFiles.writeFully(channel, concat(chunks))
            channel.force(true)
        }
        var pos = end
        entries.forEachIndexed { k, entry ->
            offsets += pos
            log += entry
            pos += chunks[k].size
        }
        end = pos
    }

    /**
     * Removes the entries from [index] through [lastIndex] from the log file, fsyncing the truncation before returning.
     * Requires [index] to be greater than the compacted index; a no-op when `index > lastIndex()`.
     */
    override fun truncateFrom(index: Long) {
        require(index > compactedIndex) { "truncate index $index must be above compacted index $compactedIndex" }
        if (index > lastIndex()) return
        val k = (index - compactedIndex - 1).toInt()
        val off = offsets[k]
        truncateLogFile(off)
        log.subList(k, log.size).clear()
        offsets.subList(k, offsets.size).clear()
        end = off
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

    /** Stores a copy of [data] with [meta] on disk, replacing any previous snapshot; the log is not modified. */
    override fun saveSnapshot(
        meta: SnapshotMeta,
        data: ByteArray,
    ) {
        val copy = data.copyOf()
        AtomicFiles.write(snapshotDataPath, copy)
        val file = SnapshotFile(meta, copy.size.toLong(), LogCodec.crc32(copy))
        AtomicFiles.write(
            snapshotMetaPath,
            LogCodec.json.encodeToString(SnapshotFile.serializer(), file).encodeToByteArray(),
        )
        snapshot = Snapshot(meta, copy)
    }

    /** Returns a copy of the saved snapshot read from disk, or null when none exists. */
    override fun loadSnapshot(): Snapshot? = snapshot?.let { Snapshot(it.meta, it.data.copyOf()) }

    /**
     * Drops all entries with indexes at or below [index] by rewriting the log file atomically.
     * Requires a saved snapshot with `index <= meta.lastIndex`. [index] may exceed [lastIndex]; in that case the log
     * becomes empty and `lastIndex()` equals [index]. A no-op when [index] is at or below the compacted index.
     */
    override fun compactTo(index: Long) {
        val saved = snapshot
        require(saved != null && index <= saved.meta.lastIndex) {
            "compaction to $index requires a saved snapshot covering it"
        }
        if (index <= compactedIndex) return
        val drop = minOf(index - compactedIndex, log.size.toLong()).toInt()
        val remaining = log.subList(drop, log.size).toList()
        val chunks = remaining.map { LogCodec.record(it) }
        AtomicFiles.write(logPath, concat(listOf(LogCodec.header(index)) + chunks))
        log.clear()
        offsets.clear()
        var pos = LogCodec.HEADER_SIZE.toLong()
        remaining.forEachIndexed { k, entry ->
            offsets += pos
            log += entry
            pos += chunks[k].size
        }
        end = pos
        compactedIndex = index
    }

    private fun readSnapshot(): Snapshot? {
        if (!Files.exists(snapshotMetaPath)) return null
        val file = LogCodec.json.decodeFromString(SnapshotFile.serializer(), Files.readString(snapshotMetaPath))
        val data = Files.readAllBytes(snapshotDataPath)
        check(data.size.toLong() == file.dataLength && LogCodec.crc32(data) == file.dataCrc) {
            "snapshot.bin does not match snapshot.meta.json"
        }
        return Snapshot(file.meta, data)
    }

    private fun truncateLogFile(size: Long) {
        FileChannel.open(logPath, StandardOpenOption.WRITE).use {
            it.truncate(size)
            it.force(true)
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val buf = ByteBuffer.allocate(parts.sumOf { it.size })
        parts.forEach { buf.put(it) }
        return buf.array()
    }

    /** Names of the files of a [FileStorage] directory. */
    internal companion object {
        const val STATE_FILE = "state.json"
        const val LOG_FILE = "log.dat"
        const val SNAPSHOT_DATA_FILE = "snapshot.bin"
        const val SNAPSHOT_META_FILE = "snapshot.meta.json"
    }
}
