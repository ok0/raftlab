package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

class CoreInfoTest {
    @Test
    fun labelsNode() {
        CoreInfo.nodeLabel(NodeId("n1")) shouldBe "node:n1"
    }

    @Test
    fun namesModule() {
        CoreInfo.MODULE shouldBe "raftlab-core"
    }
}
