package io.raftlab.net

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.kv.KvResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class RestartTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun stoppingAFollowerKeepsServing() {
        TestCluster(tmp).use { c ->
            c.startAll()
            val leader = c.awaitLeader()
            c.stop(c.ids.first { it != leader })
            val client = c.client()
            runBlocking {
                client.put("a", "1") shouldBe KvResult.Ok(null)
                client.get("a") shouldBe KvResult.Ok("1")
            }
        }
    }

    @Test
    fun stoppingTheLeaderElectsANewOne() {
        TestCluster(tmp).use { c ->
            c.startAll()
            val old = c.awaitLeader()
            val client = c.client()
            runBlocking { client.put("a", "1") shouldBe KvResult.Ok(null) }
            c.stop(old)
            val new = c.awaitLeader()
            new shouldNotBe old
            runBlocking {
                client.put("b", "2") shouldBe KvResult.Ok(null)
                client.get("a") shouldBe KvResult.Ok("1")
                client.get("b") shouldBe KvResult.Ok("2")
            }
        }
    }

    @Test
    fun restartedNodeRecoversFromDiskAndCatchesUp() {
        TestCluster(tmp).use { c ->
            c.startAll()
            val leader = c.awaitLeader()
            val client = c.client()
            runBlocking { client.put("before", "1") shouldBe KvResult.Ok(null) }
            val f = c.ids.first { it != leader }
            NetTest.awaitUntil(message = "follower applies 'before'") {
                c.runtime(f).kvData()["before"] == "1"
            }
            val termBefore = c.runtime(f).status().term
            c.stop(f)
            Files.exists(c.dataDir(f).resolve("log.dat")) shouldBe true
            runBlocking {
                client.put("during", "2") shouldBe KvResult.Ok(null)
                client.get("during") shouldBe KvResult.Ok("2")
            }
            val r = c.start(f)
            (r.status().term >= termBefore) shouldBe true
            NetTest.awaitUntil(10_000, "restarted node catches up") {
                r.kvData() == mapOf("before" to "1", "during" to "2")
            }
            runBlocking { client.get("before") shouldBe KvResult.Ok("1") }
        }
    }
}
