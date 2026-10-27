package io.raftlab.storage

import io.kotest.matchers.shouldBe
import io.raftlab.core.EntryPayload
import io.raftlab.core.LogEntry
import io.raftlab.core.PersistentState
import io.raftlab.core.SnapshotMeta
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class FileStorageDeterminismTest {
    @TempDir
    lateinit var tmp: Path

    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")

    private fun e(
        i: Long,
        t: Long,
    ): LogEntry = LogEntry(i, t, EntryPayload.Command(byteArrayOf(i.toByte())))

    private fun apply(dir: Path) {
        val storage = FileStorage(dir)
        storage.saveState(PersistentState(2, n1))
        storage.append(
            listOf(
                LogEntry(1, 1, EntryPayload.NoOp),
                LogEntry(2, 1, EntryPayload.Command(byteArrayOf(1, 2))),
                LogEntry(3, 1, EntryPayload.Config(setOf(n1, n2))),
                LogEntry(4, 2, EntryPayload.Command(byteArrayOf())),
                LogEntry(5, 2, EntryPayload.Command(ByteArray(300) { it.toByte() })),
                LogEntry(6, 2, EntryPayload.NoOp),
            ),
        )
        storage.truncateFrom(5)
        storage.append(listOf(e(5, 3)))
        storage.saveSnapshot(SnapshotMeta(3, 1, setOf(n1, n2)), byteArrayOf(5))
        storage.compactTo(3)
        storage.saveState(PersistentState(3, null))
    }

    @Test
    fun sameOperationsSameContentsInDifferentDirectories() {
        val first = tmp.resolve("x")
        val second = tmp.resolve("deeper/other-name")
        apply(first)
        apply(second)

        val a = FileStorage(first)
        val b = FileStorage(second)
        a.loadState() shouldBe b.loadState()
        a.lastIndex() shouldBe b.lastIndex()
        a.entries(4, a.lastIndex() + 1) shouldBe b.entries(4, b.lastIndex() + 1)
        a.loadSnapshot() shouldBe b.loadSnapshot()

        val names =
            listOf(
                FileStorage.STATE_FILE,
                FileStorage.LOG_FILE,
                FileStorage.SNAPSHOT_DATA_FILE,
                FileStorage.SNAPSHOT_META_FILE,
            )
        for (name in names) {
            val left = Files.readAllBytes(first.resolve(name))
            val right = Files.readAllBytes(second.resolve(name))
            left.contentEquals(right) shouldBe true
        }
    }
}
