package io.raftlab.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.raftlab.kv.KvResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class ClientTimeoutTest {
    @TempDir
    lateinit var tmp: Path

    private fun elapsedMillis(since: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)

    @Test
    fun noReachableNodeTimesOut() {
        val cfg = NetTest.cluster(NetTest.freePorts(3))
        TcpKvClient(cfg, attemptTimeoutMillis = 200, callTimeoutMillis = 1_500).use { client ->
            val t0 = System.nanoTime()
            shouldThrow<KvClientTimeoutException> { runBlocking { client.put("k", "v") } }
            elapsedMillis(t0) shouldBeLessThan 5_000L
        }
    }

    @Test
    fun minorityUpTimesOut() {
        TestCluster(tmp).use { c ->
            c.start(c.ids[0])
            val client = c.client(200, 2_000)
            val t0 = System.nanoTime()
            shouldThrow<KvClientTimeoutException> { runBlocking { client.put("k", "v") } }
            elapsedMillis(t0) shouldBeLessThan 6_000L
        }
    }

    @Test
    fun callAfterTimeoutStillUsable() {
        TestCluster(tmp).use { c ->
            c.start(c.ids[0])
            val client = c.client(200, 2_000)
            shouldThrow<KvClientTimeoutException> { runBlocking { client.put("k", "v") } }
            c.start(c.ids[1])
            c.awaitLeader()
            NetTest.awaitUntil(15_000, "client recovers after timeout") {
                runCatching { runBlocking { client.put("k2", "v") } }.getOrNull() == KvResult.Ok(null)
            }
        }
    }

    @Test
    fun closedClientFailsFastAndReleasesSockets() {
        TestCluster(tmp, size = 1).use { c ->
            c.startAll()
            c.awaitLeader()
            val client = c.client()
            runBlocking { client.put("a", "1") } shouldBe KvResult.Ok(null)
            client.openConnections shouldBe 1
            client.close()
            client.openConnections shouldBe 0
            val t0 = System.nanoTime()
            shouldThrow<IllegalStateException> { runBlocking { client.get("a") } }
            elapsedMillis(t0) shouldBeLessThan 1_000L
        }
    }
}
