package io.raftlab.kv

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

class KvCodecTest {
    private val commands =
        listOf(
            KvCommand("c1", 1, KvOp.Put("k", "v")),
            KvCommand("c1", 2, KvOp.Get("k")),
            KvCommand("c1", 3, KvOp.Delete("k")),
            KvCommand("c1", 4, KvOp.Cas("k", null, "w")),
        )

    @Test
    fun encodesCommandGoldenPerOp() {
        KvCodec.encodeCommand(KvCommand("c1", 1, KvOp.Put("k", "v"))).decodeToString() shouldBe
            """{"clientId":"c1","seq":1,"op":{"type":"Put","key":"k","value":"v"}}"""
        KvCodec.encodeCommand(KvCommand("c1", 2, KvOp.Get("k"))).decodeToString() shouldBe
            """{"clientId":"c1","seq":2,"op":{"type":"Get","key":"k"}}"""
        KvCodec.encodeCommand(KvCommand("c1", 3, KvOp.Delete("k"))).decodeToString() shouldBe
            """{"clientId":"c1","seq":3,"op":{"type":"Delete","key":"k"}}"""
        KvCodec.encodeCommand(KvCommand("c1", 4, KvOp.Cas("k", null, "w"))).decodeToString() shouldBe
            """{"clientId":"c1","seq":4,"op":{"type":"Cas","key":"k","expected":null,"new":"w"}}"""
    }

    @Test
    fun commandRoundTripsAndIsByteStable() {
        for (c in commands) {
            val first = KvCodec.encodeCommand(c)
            KvCodec.decodeCommand(first) shouldBe c
            first.contentEquals(KvCodec.encodeCommand(c)) shouldBe true
        }
    }

    @Test
    fun resultsRoundTripWithGoldens() {
        val cases =
            listOf(
                KvResult.Ok("v") to """{"type":"Ok","value":"v"}""",
                KvResult.Ok(null) to """{"type":"Ok","value":null}""",
                KvResult.CasFailed("x") to """{"type":"CasFailed","actual":"x"}""",
                KvResult.NotLeader(NodeId("n2")) to """{"type":"NotLeader","hint":"n2"}""",
                KvResult.NotLeader(null) to """{"type":"NotLeader","hint":null}""",
                KvResult.Timeout to """{"type":"Timeout"}""",
            )
        for ((result, golden) in cases) {
            KvCodec.encodeResult(result).decodeToString() shouldBe golden
            KvCodec.decodeResult(KvCodec.encodeResult(result)) shouldBe result
        }
    }

    @Test
    fun requestAndResponseRoundTripWithGoldens() {
        val request = ClientRequest(7, KvCommand("c1", 1, KvOp.Put("k", "v")))
        KvCodec.encodeRequest(request) shouldBe
            """{"requestId":7,"command":{"clientId":"c1","seq":1,"op":{"type":"Put","key":"k","value":"v"}}}"""
        KvCodec.decodeRequest(KvCodec.encodeRequest(request)) shouldBe request

        val responses =
            listOf(
                ClientResponse(7, KvResult.Ok("v")),
                ClientResponse(7, KvResult.CasFailed("x")),
                ClientResponse(7, KvResult.NotLeader(NodeId("n3"))),
                ClientResponse(7, KvResult.Timeout),
            )
        KvCodec.encodeResponse(responses[0]) shouldBe """{"requestId":7,"result":{"type":"Ok","value":"v"}}"""
        for (response in responses) {
            KvCodec.decodeResponse(KvCodec.encodeResponse(response)) shouldBe response
        }
    }

    @Test
    fun rejectsInvalidCommandBytes() {
        shouldThrow<IllegalArgumentException> { KvCodec.decodeCommand("not json".encodeToByteArray()) }
        shouldThrow<IllegalArgumentException> {
            KvCodec.decodeCommand("""{"clientId":"c1","seq":0,"op":{"type":"Get","key":"k"}}""".encodeToByteArray())
        }
        shouldThrow<IllegalArgumentException> { KvCodec.decodeCommand(byteArrayOf(-1, -2)) }
    }
}
