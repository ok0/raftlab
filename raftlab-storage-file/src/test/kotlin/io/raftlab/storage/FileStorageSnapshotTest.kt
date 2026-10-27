package io.raftlab.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.core.EntryPayload
import io.raftlab.core.Input
import io.raftlab.core.LogEntry
import io.raftlab.core.RaftConfig
import io.raftlab.core.RaftNode
import io.raftlab.core.SnapshotMeta
import io.raftlab.core.SplitMix64
import io.raftlab.core.StateMachine
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

internal class FileStorageSnapshotTest {
    @TempDir
    lateinit var tmp: Path

    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")

    private fun e(
        i: Long,
        t: Long,
    ): LogEntry = LogEntry(i, t, EntryPayload.Command(byteArrayOf(i.toByte())))

    private fun meta(
        i: Long,
        t: Long,
    ): SnapshotMeta = SnapshotMeta(i, t, setOf(n1, n2))

    private fun appendFive(storage: FileStorage) {
        storage.append(listOf(e(1, 1), e(2, 1), e(3, 2), e(4, 2), e(5, 3)))
    }

    private class ListStateMachine : StateMachine {
        val applied = mutableListOf<Long>()

        override fun apply(
            index: Long,
            command: ByteArray,
        ): ByteArray {
            applied += index
            return ByteArray(0)
        }

        override fun snapshot(): ByteArray = applied.joinToString(",").encodeToByteArray()

        override fun restore(data: ByteArray) {
            applied.clear()
            val text = data.decodeToString()
            if (text.isNotEmpty()) text.split(",").forEach { applied += it.toLong() }
        }
    }

    private fun tickUntilLeader(node: RaftNode) {
        var ticks = 0
        while (node.role != Role.LEADER) {
            check(ticks++ < 25) { "no leader after 25 ticks" }
            node.step(Input.Tick)
        }
    }

    @Test
    fun snapshotSavedButLogNotCompactedOpensCompacted() {
        val dir = tmp.resolve("d")
        val first = FileStorage(dir)
        appendFive(first)
        first.saveSnapshot(meta(3, 2), byteArrayOf(1))

        val reopened = FileStorage(dir)
        reopened.lastIndex() shouldBe 5L
        reopened.entries(4, 6) shouldBe listOf(e(4, 2), e(5, 3))
        shouldThrow<IllegalArgumentException> { reopened.entries(3, 4) }
        reopened.termAt(3) shouldBe 2L
        reopened.termAt(2) shouldBe null

        val bytes = Files.readAllBytes(dir.resolve(FileStorage.LOG_FILE))
        ByteBuffer.wrap(bytes, 4, 8).long shouldBe 3L
    }

    @Test
    fun snapshotBeyondLogNotCompactedOpensEmptyWithBase() {
        val dir = tmp.resolve("d")
        val first = FileStorage(dir)
        first.append(listOf(e(1, 1), e(2, 1)))
        first.saveSnapshot(meta(10, 4), byteArrayOf())

        val reopened = FileStorage(dir)
        reopened.lastIndex() shouldBe 10L
        reopened.termAt(10) shouldBe 4L
        reopened.append(listOf(e(11, 4)))

        val again = FileStorage(dir)
        again.lastIndex() shouldBe 11L
    }

    @Test
    fun reopenedCompactedStorageIsIdentical() {
        val dir = tmp.resolve("d")
        val first = FileStorage(dir)
        appendFive(first)
        first.saveSnapshot(meta(3, 2), byteArrayOf(1))
        first.compactTo(3)

        val a = FileStorage(dir)
        val bytesA = Files.readAllBytes(dir.resolve(FileStorage.LOG_FILE))
        val b = FileStorage(dir)
        val bytesB = Files.readAllBytes(dir.resolve(FileStorage.LOG_FILE))

        b.loadSnapshot() shouldBe a.loadSnapshot()
        b.loadSnapshot()?.meta?.members shouldBe a.loadSnapshot()?.meta?.members
        b.entries(4, 6) shouldBe a.entries(4, 6)
        b.lastIndex() shouldBe a.lastIndex()
        bytesB.contentEquals(bytesA) shouldBe true
    }

    @Test
    fun tornTrailingRecordOnCompactedLogIsDiscarded() {
        val dir = tmp.resolve("d")
        val first = FileStorage(dir)
        appendFive(first)
        first.saveSnapshot(meta(3, 2), byteArrayOf(1))
        first.compactTo(3)
        first.append(listOf(e(6, 3)))

        val logPath = dir.resolve(FileStorage.LOG_FILE)
        val bytes = Files.readAllBytes(logPath)
        Files.write(logPath, bytes.copyOf(bytes.size - 3))

        val reopened = FileStorage(dir)
        reopened.lastIndex() shouldBe 5L
        reopened.termAt(3) shouldBe 2L
        reopened.entries(4, 6) shouldBe listOf(e(4, 2), e(5, 3))

        reopened.append(listOf(e(6, 5)))
        val again = FileStorage(dir)
        again.termAt(6) shouldBe 5L
    }

    @Test
    fun raftNodeRestartsFromFileSnapshot() {
        val dir = tmp.resolve("d")
        val config = RaftConfig(snapshotThreshold = 2)
        val members = setOf(n1)
        val machine = ListStateMachine()
        val node = RaftNode(n1, members, config, FileStorage(dir), machine, SplitMix64(1), TraceSink { })
        tickUntilLeader(node)
        node.step(Input.Propose(1, byteArrayOf(1)))
        node.step(Input.Propose(2, byteArrayOf(2)))
        node.snapshotIndex shouldBe 3L
        node.step(Input.Propose(3, byteArrayOf(3)))

        val restartedMachine = ListStateMachine()
        val restarted =
            RaftNode(n1, members, config, FileStorage(dir), restartedMachine, SplitMix64(1), TraceSink { })
        restartedMachine.applied shouldBe listOf(2L, 3L)
        restarted.commitIndex shouldBe 3L
        restarted.lastApplied shouldBe 3L
        restarted.snapshotIndex shouldBe 3L
        restarted.members shouldBe setOf(n1)
        restarted.logSnapshotForChecks().map { it.index } shouldBe listOf(4L)

        tickUntilLeader(restarted)
        restartedMachine.applied shouldBe listOf(2L, 3L, 4L)
    }
}
