package io.raftlab.net

import io.kotest.matchers.shouldBe
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Path

internal class MalformedFrameTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun oversizedLength() {
        rejects(ByteBuffer.allocate(4).putInt(FrameCodec.MAX_FRAME_BYTES + 1).array(), false)
    }

    @Test
    fun negativeLength() {
        rejects(ByteBuffer.allocate(4).putInt(-1).array(), false)
    }

    @Test
    fun undecodableJson() {
        rejects(prefixed("hello".toByteArray()), false)
    }

    @Test
    fun wrongShapeJson() {
        rejects(prefixed("""{"type":"bogus"}""".toByteArray()), false)
    }

    @Test
    fun closedMidFrame() {
        rejects(ByteBuffer.allocate(4 + 10).putInt(100).array(), true)
    }

    private fun prefixed(body: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + body.size)
        buf.putInt(body.size)
        buf.put(body)
        return buf.array()
    }

    private fun rejects(
        bad: ByteArray,
        closeOutput: Boolean,
    ) {
        TestCluster(tmp, size = 1).use { c ->
            c.startAll()
            c.awaitLeader()
            val addr = c.config.address(c.ids[0])
            val kept = NetTest.connect(addr)
            try {
                val put = NetTest.request(kept, ClientRequest(1, KvCommand("raw", 1, KvOp.Put("a", "1"))))
                put.result shouldBe KvResult.Ok(null)
                Socket().use { s ->
                    s.connect(addr, 2_000)
                    s.getOutputStream().write(bad)
                    s.getOutputStream().flush()
                    if (closeOutput) s.shutdownOutput()
                    NetTest.assertClosedByServer(s)
                }
                val get = NetTest.request(kept, ClientRequest(2, KvCommand("raw", 2, KvOp.Get("a"))))
                get.result shouldBe KvResult.Ok("1")
            } finally {
                kept.close()
            }
            c.client().use { client ->
                runBlocking {
                    client.put("b", "2") shouldBe KvResult.Ok(null)
                    client.get("b") shouldBe KvResult.Ok("2")
                }
            }
            c.runtime(c.ids[0]).isRunning shouldBe true
            c.runtime(c.ids[0]).failure shouldBe null
        }
    }
}
