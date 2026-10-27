package io.raftlab.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

internal class ClusterConfigTest {
    @Test
    fun parsesMembersInDeclarationOrder() {
        val config = ClusterConfig.parse("n1=127.0.0.1:7001,n2=127.0.0.1:7002")
        config.ids.map { it.value } shouldBe listOf("n1", "n2")
        config.address(NodeId("n1")).port shouldBe 7001
        config.address(NodeId("n1")).hostString shouldBe "127.0.0.1"
        config.address(NodeId("n2")).port shouldBe 7002
        ClusterConfig.parse("n2=127.0.0.1:7002,n1=127.0.0.1:7001").ids.map { it.value } shouldBe listOf("n2", "n1")
    }

    @Test
    fun trimsWhitespaceAndRoundTrips() {
        val config = ClusterConfig.parse(" n1 = 127.0.0.1:7001 , n2=localhost:7002 ")
        config.ids.map { it.value } shouldBe listOf("n1", "n2")
        config.address(NodeId("n2")).hostString shouldBe "localhost"
        ClusterConfig.parse(config.toString()).toString() shouldBe config.toString()
    }

    @Test
    fun rejectsMalformedInput() {
        val malformed =
            listOf(
                "",
                "   ",
                "n1",
                "n1127.0.0.1:7001",
                "=127.0.0.1:7001",
                "n1=127.0.0.1",
                "n1=:7001",
                "n1=127.0.0.1:abc",
                "n1=127.0.0.1:0",
                "n1=127.0.0.1:70000",
                "n1=127.0.0.1:7001,,n2=127.0.0.1:7002",
                "n1=127.0.0.1:7001,n1=127.0.0.1:7002",
            )
        for (text in malformed) {
            shouldThrow<IllegalArgumentException> { ClusterConfig.parse(text) }
        }
        val duplicate =
            shouldThrow<IllegalArgumentException> { ClusterConfig.parse("n1=127.0.0.1:7001,n1=127.0.0.1:7002") }
        duplicate.message.orEmpty() shouldContain "duplicate"
        val empty = shouldThrow<IllegalArgumentException> { ClusterConfig.parse("") }
        empty.message.orEmpty() shouldContain "empty"
    }

    @Test
    fun rejectsEmptyMapAndUnknownId() {
        shouldThrow<IllegalArgumentException> { ClusterConfig(emptyMap()) }
        shouldThrow<IllegalArgumentException> {
            ClusterConfig.parse("n1=127.0.0.1:7001").address(NodeId("n9"))
        }
    }
}
