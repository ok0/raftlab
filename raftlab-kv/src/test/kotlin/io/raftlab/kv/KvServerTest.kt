package io.raftlab.kv

import io.kotest.matchers.shouldBe
import io.raftlab.core.AppendEntries
import io.raftlab.core.Effect
import io.raftlab.core.Input
import io.raftlab.core.RaftNode
import io.raftlab.core.Rejection
import io.raftlab.core.RequestVoteResponse
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class KvServerTest {
    private fun tickUntil(
        node: RaftNode,
        server: KvServer,
        role: Role,
        max: Int,
    ) {
        repeat(max) {
            if (node.role != role) server.onEffects(node.step(Input.Tick))
        }
    }

    private fun deposedLeader(): Pair<RaftNode, KvServer> {
        val node = kvNode(n1, setOf(n1, n2, n3), KvStateMachine())
        val server = KvServer(node)
        tickUntil(node, server, Role.CANDIDATE, 100)
        node.step(Input.Receive(RequestVoteResponse(node.currentTerm, n2, n1, true, false)))
        return node to server
    }

    @Test
    fun singleNodeLeaderPutThenGet() {
        val m = KvStateMachine()
        val node = kvNode(n1, setOf(n1), m)
        val server = KvServer(node)
        tickUntil(node, server, Role.LEADER, 50)
        node.role shouldBe Role.LEADER
        server.onClientRequest(ClientRequest(10, KvCommand("c1", 1, KvOp.Put("k", "v")))).replies() shouldBe
            listOf(ClientResponse(10, KvResult.Ok(null)))
        server.onClientRequest(ClientRequest(11, KvCommand("c1", 2, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(11, KvResult.Ok("v")))
    }

    @Test
    fun followerAppliedEntriesProduceNoOutput() {
        val server = KvServer(kvNode(n1, setOf(n1, n2, n3), KvStateMachine()))
        val applied =
            listOf(
                Effect.Applied(null, 5, KvCodec.encodeResult(KvResult.Ok(null))),
                Effect.Applied(999, 6, KvCodec.encodeResult(KvResult.Ok(null))),
            )
        server.onEffects(applied) shouldBe emptyList()
    }

    @Test
    fun followerWithoutLeaderAnswersNotLeaderNull() {
        val server = KvServer(kvNode(n1, setOf(n1, n2, n3), KvStateMachine()))
        server.onClientRequest(ClientRequest(1, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(1, KvResult.NotLeader(null)))
    }

    @Test
    fun followerWithKnownLeaderAnswersHint() {
        val node = kvNode(n1, setOf(n1, n2, n3), KvStateMachine())
        val server = KvServer(node)
        node.step(Input.Receive(AppendEntries(1, n2, n1, 0, 0, emptyList(), 0, 0)))
        server.onClientRequest(ClientRequest(1, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(1, KvResult.NotLeader(n2)))
    }

    @Test
    fun candidateAnswersNotLeaderNull() {
        val node = kvNode(n1, setOf(n1, n2, n3), KvStateMachine())
        val server = KvServer(node)
        tickUntil(node, server, Role.CANDIDATE, 100)
        node.role shouldBe Role.CANDIDATE
        server.onClientRequest(ClientRequest(1, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(1, KvResult.NotLeader(null)))
    }

    @Test
    fun deposedLeaderAnswersTimeoutOnce() {
        val (node, server) = deposedLeader()
        node.role shouldBe Role.LEADER
        val outputs = server.onClientRequest(ClientRequest(42, KvCommand("c1", 1, KvOp.Put("k", "v"))))
        outputs.any { it is KvServerOutput.NodeEffect && it.effect is Effect.Send } shouldBe true
        outputs.replies() shouldBe emptyList()
        val effects =
            node.step(Input.Receive(AppendEntries(node.currentTerm + 1, n2, n1, 0, 0, emptyList(), 0, 0)))
        server.onEffects(effects).replies() shouldBe listOf(ClientResponse(42, KvResult.Timeout))
        node.role shouldBe Role.FOLLOWER
        server.onEffects(listOf(Effect.Applied(1, 2, KvCodec.encodeResult(KvResult.Ok(null))))) shouldBe emptyList()
    }

    @Test
    fun unknownRejectionMapsToTimeout() {
        val (node, server) = deposedLeader()
        server.onClientRequest(ClientRequest(42, KvCommand("c1", 1, KvOp.Put("k", "v"))))
        val effects =
            node.step(Input.Receive(AppendEntries(node.currentTerm + 1, n2, n1, 0, 0, emptyList(), 0, 0)))
        server.onEffects(effects).replies() shouldBe listOf(ClientResponse(42, KvResult.Timeout))
        server.onEffects(listOf(Effect.Rejected(1, Rejection.NotLeader(n3)))) shouldBe emptyList()
    }
}
