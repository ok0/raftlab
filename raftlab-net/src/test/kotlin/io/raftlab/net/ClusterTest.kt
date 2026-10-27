package io.raftlab.net

import io.kotest.matchers.shouldBe
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class ClusterTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun threeNodeClusterElectsAndServesKv() {
        TestCluster(tmp).use { c ->
            c.startAll()
            c.awaitLeader(10_000)
            val client = c.client()
            runBlocking<Unit> {
                client.put("k", "v") shouldBe KvResult.Ok(null)
                client.get("k") shouldBe KvResult.Ok("v")
                client.cas("k", "v", "w") shouldBe KvResult.Ok("w")
                client.cas("k", "zz", "q") shouldBe KvResult.CasFailed("w")
                client.delete("k") shouldBe KvResult.Ok("w")
                client.get("k") shouldBe KvResult.Ok(null)
            }
        }
    }

    @Test
    fun followerAnswersNotLeaderWithHint() {
        TestCluster(tmp).use { c ->
            c.startAll()
            val leader = c.awaitLeader()
            val follower = c.ids.first { it != leader }
            NetTest.connect(c.config.address(follower)).use { conn ->
                val response = NetTest.request(conn, ClientRequest(1, KvCommand("raw", 1, KvOp.Put("x", "1"))))
                response.result shouldBe KvResult.NotLeader(leader)
            }
        }
    }

    @Test
    fun clientStartingAtFollowerIsRedirected() {
        TestCluster(tmp).use { c ->
            c.startAll()
            var leader = c.awaitLeader()
            val first = c.ids.minBy { it.value }
            if (leader == first) {
                c.stop(first)
                leader = c.awaitLeader()
                c.start(first)
                NetTest.awaitUntil(message = "restarted node learns leader") {
                    c.runtime(first).status().leaderHint == leader
                }
            }
            val client = c.client()
            client.leaderGuess shouldBe first
            runBlocking<Unit> {
                client.put("r", "1") shouldBe KvResult.Ok(null)
            }
            client.leaderGuess shouldBe leader
            runBlocking<Unit> {
                client.get("r") shouldBe KvResult.Ok("1")
            }
        }
    }

    @Test
    fun retryWithSameSeqIsAppliedOnce() {
        TestCluster(tmp).use { c ->
            c.startAll()
            val leader = c.awaitLeader()
            val client = c.client()
            NetTest.connect(c.config.address(leader)).use { raw ->
                val first = ClientRequest(1, KvCommand("dup", 1, KvOp.Put("d", "x")))
                NetTest.request(raw, first).result shouldBe KvResult.Ok(null)
                runBlocking<Unit> {
                    client.put("d", "y") shouldBe KvResult.Ok(null)
                }
                val retry = ClientRequest(2, KvCommand("dup", 1, KvOp.Put("d", "x")))
                NetTest.request(raw, retry).result shouldBe KvResult.Ok(null)
                runBlocking<Unit> {
                    client.get("d") shouldBe KvResult.Ok("y")
                }
            }
            c.runtime(leader).kvData()["d"] shouldBe "y"
        }
    }
}
