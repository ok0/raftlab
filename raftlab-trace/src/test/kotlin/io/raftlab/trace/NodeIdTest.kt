package io.raftlab.trace

import io.kotest.matchers.shouldBe
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
}
