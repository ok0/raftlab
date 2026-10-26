package io.raftlab.trace

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class NodeIdTest {
    @Test
    fun sortsByValue() {
        listOf(NodeId("n2"), NodeId("n1")).sorted() shouldBe listOf(NodeId("n1"), NodeId("n2"))
    }

    @Test
    fun keepsValue() {
        NodeId("n1").value shouldBe "n1"
    }

    @Test
    fun serializesAsPlainString() {
        Json.encodeToString(NodeId.serializer(), NodeId("n1")) shouldBe "\"n1\""
        Json.decodeFromString(NodeId.serializer(), "\"n2\"") shouldBe NodeId("n2")
    }
}
