package io.raftlab.storage

import io.kotest.matchers.shouldBe
import io.raftlab.core.PersistentState
import io.raftlab.core.Snapshot
import io.raftlab.core.SnapshotMeta
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class AtomicStateTest {
    @TempDir
    lateinit var tmp: Path

    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")

    private fun meta(
        i: Long,
        t: Long,
    ): SnapshotMeta = SnapshotMeta(i, t, setOf(n1, n2))

    @Test
    fun leftoverTempStateIsIgnored() {
        val dir = tmp.resolve("s")
        val first = FileStorage(dir)
        first.saveState(PersistentState(3, n1))
        first.saveState(PersistentState(4, n2))
        val leftover = dir.resolve(FileStorage.STATE_FILE + ".tmp")
        Files.writeString(leftover, """{"term":99""")

        val reopened = FileStorage(dir)
        reopened.loadState() shouldBe PersistentState(4, n2)
        Files.exists(leftover) shouldBe false
    }

    @Test
    fun completeLeftoverTempIsAlsoIgnored() {
        val dir = tmp.resolve("s")
        val first = FileStorage(dir)
        first.saveState(PersistentState(3, n1))
        first.saveState(PersistentState(4, n2))
        val leftover = dir.resolve(FileStorage.STATE_FILE + ".tmp")
        Files.writeString(leftover, """{"term":99,"votedFor":"n3"}""")

        val reopened = FileStorage(dir)
        reopened.loadState() shouldBe PersistentState(4, n2)
        Files.exists(leftover) shouldBe false
    }

    @Test
    fun leftoverSnapshotTempIsIgnored() {
        val dir = tmp.resolve("s")
        val first = FileStorage(dir)
        first.saveSnapshot(meta(2, 1), byteArrayOf(1, 2))
        Files.write(dir.resolve(FileStorage.SNAPSHOT_DATA_FILE + ".tmp"), byteArrayOf(7, 7, 7))
        Files.writeString(dir.resolve(FileStorage.SNAPSHOT_META_FILE + ".tmp"), "{")

        val reopened = FileStorage(dir)
        reopened.loadSnapshot() shouldBe Snapshot(meta(2, 1), byteArrayOf(1, 2))
        Files.exists(dir.resolve(FileStorage.SNAPSHOT_DATA_FILE + ".tmp")) shouldBe false
        Files.exists(dir.resolve(FileStorage.SNAPSHOT_META_FILE + ".tmp")) shouldBe false
    }
}
