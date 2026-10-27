package io.raftlab.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.core.SnapshotMeta
import io.raftlab.storage.FileStorage
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

internal class RuntimeLifecycleTest {
    @TempDir
    lateinit var tmp: Path

    private val n1 = NodeId("n1")
    private val port = NetTest.freePorts(1)[0]
    private val cfg = NetTest.cluster(listOf(port))

    private fun awaitSingleLeader(rt: NodeRuntime) {
        NetTest.awaitUntil(message = "n1 did not become leader") { rt.status().role == Role.LEADER }
    }

    private fun assertPortBindable() {
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", port))
        }
    }

    @Test
    fun stopReleasesPortAndThreadsRepeatedly() {
        repeat(3) {
            val rt = NodeRuntime(n1, cfg, tmp.resolve("n1"), NetTest.TICK_MILLIS)
            rt.start()
            try {
                awaitSingleLeader(rt)
                rt.stop()
            } finally {
                rt.stop()
            }
            rt.isRunning shouldBe false
            shouldThrow<IllegalStateException> { rt.status() }
            NetTest.awaitUntil(5_000, "runtime threads exit") { NetTest.liveThreads("raftlab-n1-").isEmpty() }
        }
        assertPortBindable()
    }

    @Test
    fun startTwiceFails() {
        val rt = NodeRuntime(n1, cfg, tmp.resolve("n1"), NetTest.TICK_MILLIS)
        rt.start()
        try {
            shouldThrow<IllegalStateException> { rt.start() }
        } finally {
            rt.stop()
        }
    }

    @Test
    fun traceFileHasNodeStartedAndRoleChanged() {
        val path = tmp.resolve("trace").resolve("n1.jsonl")
        val rt = NodeRuntime(n1, cfg, tmp.resolve("n1"), NetTest.TICK_MILLIS, path)
        rt.start()
        try {
            awaitSingleLeader(rt)
        } finally {
            rt.stop()
        }
        val events = TraceCodec.decode(Files.readString(path))
        events.any { it is NodeStarted && it.node == n1 } shouldBe true
        events.any { it is RoleChanged } shouldBe true
        events.map { it.seq } shouldBe (0L until events.size.toLong()).toList()
    }

    @Test
    fun noTraceFileWithoutPath() {
        val rt = NodeRuntime(n1, cfg, tmp.resolve("data"), NetTest.TICK_MILLIS)
        rt.start()
        try {
            awaitSingleLeader(rt)
        } finally {
            rt.stop()
        }
        Files.list(tmp).use { s -> s.map { it.fileName.toString() }.toList() } shouldBe listOf("data")
        Files.walk(tmp).use { s -> s.noneMatch { it.toString().endsWith(".jsonl") } } shouldBe true
    }

    @Test
    fun corruptSnapshotFailsStart() {
        val dir = tmp.resolve("n1")
        FileStorage(dir).saveSnapshot(SnapshotMeta(1, 1, setOf(n1)), byteArrayOf(1, 2, 3))
        Files.write(dir.resolve("snapshot.bin"), byteArrayOf(9))
        val rt = NodeRuntime(n1, cfg, dir, NetTest.TICK_MILLIS)
        shouldThrow<IllegalStateException> { rt.start() }
        rt.isRunning shouldBe false
        assertPortBindable()
        NetTest.liveThreads("raftlab-n1-") shouldBe emptyList()
        rt.close()
    }
}
