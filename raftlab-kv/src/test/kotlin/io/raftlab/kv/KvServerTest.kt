package io.raftlab.kv

import io.kotest.matchers.shouldBe
import io.raftlab.core.AppendEntries
import io.raftlab.core.AppendEntriesResponse
import io.raftlab.core.Effect
import io.raftlab.core.InMemoryStorage
import io.raftlab.core.Input
import io.raftlab.core.RaftConfig
import io.raftlab.core.RaftNode
import io.raftlab.core.Rejection
import io.raftlab.core.RequestVoteResponse
import io.raftlab.core.SplitMix64
import io.raftlab.trace.LogAppended
import io.raftlab.trace.Role
import io.raftlab.trace.TraceRecorder
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
        val m = KvStateMachine()
        val node = kvNode(n1, setOf(n1, n2, n3), m, config = RaftConfig(preVote = false))
        val server = KvServer(node, m)
        tickUntil(node, server, Role.CANDIDATE, 100)
        node.step(Input.Receive(RequestVoteResponse(node.currentTerm, n2, n1, true, false)))
        return node to server
    }

    private fun committedLeader(): Pair<RaftNode, KvServer> {
        val (node, server) = deposedLeader()
        server.onEffects(node.step(Input.Receive(AppendEntriesResponse(node.currentTerm, n2, n1, true, 1, 0, 0, 0))))
        check(node.commitIndex == 1L)
        return node to server
    }

    @Test
    fun singleNodeLeaderPutThenGet() {
        val m = KvStateMachine()
        val node = kvNode(n1, setOf(n1), m)
        val server = KvServer(node, m)
        tickUntil(node, server, Role.LEADER, 50)
        node.role shouldBe Role.LEADER
        server.onClientRequest(ClientRequest(10, KvCommand("c1", 1, KvOp.Put("k", "v")))).replies() shouldBe
            listOf(ClientResponse(10, KvResult.Ok(null)))
        server.onClientRequest(ClientRequest(11, KvCommand("c1", 2, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(11, KvResult.Ok("v")))
    }

    @Test
    fun followerAppliedEntriesProduceNoOutput() {
        val m = KvStateMachine()
        val server = KvServer(kvNode(n1, setOf(n1, n2, n3), m), m)
        val applied =
            listOf(
                Effect.Applied(null, 5, KvCodec.encodeResult(KvResult.Ok(null))),
                Effect.Applied(999, 6, KvCodec.encodeResult(KvResult.Ok(null))),
            )
        server.onEffects(applied) shouldBe emptyList()
    }

    @Test
    fun followerWithoutLeaderAnswersNotLeaderNull() {
        val m = KvStateMachine()
        val server = KvServer(kvNode(n1, setOf(n1, n2, n3), m), m)
        server.onClientRequest(ClientRequest(1, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(1, KvResult.NotLeader(null)))
    }

    @Test
    fun followerWithKnownLeaderAnswersHint() {
        val m = KvStateMachine()
        val node = kvNode(n1, setOf(n1, n2, n3), m)
        val server = KvServer(node, m)
        node.step(Input.Receive(AppendEntries(1, n2, n1, 0, 0, emptyList(), 0, 0)))
        server.onClientRequest(ClientRequest(1, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(1, KvResult.NotLeader(n2)))
    }

    @Test
    fun candidateAnswersNotLeaderNull() {
        val m = KvStateMachine()
        val node = kvNode(n1, setOf(n1, n2, n3), m, config = RaftConfig(preVote = false))
        val server = KvServer(node, m)
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

    @Test
    fun rejectionsMapExhaustively() {
        val (_, server) = deposedLeader()
        server.onClientRequest(ClientRequest(42, KvCommand("c1", 1, KvOp.Put("k", "v"))))
        server.onEffects(listOf(Effect.Rejected(1, Rejection.Busy))).replies() shouldBe
            listOf(ClientResponse(42, KvResult.Timeout))
        server.onClientRequest(ClientRequest(43, KvCommand("c1", 2, KvOp.Put("k", "v"))))
        server.onEffects(listOf(Effect.Rejected(2, Rejection.Unknown))).replies() shouldBe
            listOf(ClientResponse(43, KvResult.Timeout))
        server.onClientRequest(ClientRequest(44, KvCommand("c1", 3, KvOp.Put("k", "v"))))
        server.onEffects(listOf(Effect.Rejected(3, Rejection.NotLeader(n3)))).replies() shouldBe
            listOf(ClientResponse(44, KvResult.NotLeader(n3)))
    }

    @Test
    fun getAppendsNoLogEntryAndKeepsSnapshotBytes() {
        val rec = TraceRecorder { 0L }
        val m = KvStateMachine()
        val node = RaftNode(n1, setOf(n1), RaftConfig(), InMemoryStorage(), m, SplitMix64(1), rec)
        val server = KvServer(node, m)
        tickUntil(node, server, Role.LEADER, 50)
        server.onClientRequest(ClientRequest(10, KvCommand("c1", 1, KvOp.Put("k", "v")))).replies() shouldBe
            listOf(ClientResponse(10, KvResult.Ok(null)))
        val log = node.logSnapshotForChecks()
        val appended = rec.events.count { it is LogAppended }
        val commit = node.commitIndex
        val snap = m.snapshot()
        server.onClientRequest(ClientRequest(11, KvCommand("c1", 2, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(11, KvResult.Ok("v")))
        node.logSnapshotForChecks() shouldBe log
        rec.events.count { it is LogAppended } shouldBe appended
        node.commitIndex shouldBe commit
        (m.snapshot().contentEquals(snap)) shouldBe true
        server.onClientRequest(ClientRequest(12, KvCommand("c1", 3, KvOp.Put("k", "w")))).replies() shouldBe
            listOf(ClientResponse(12, KvResult.Ok(null)))
        server.onClientRequest(ClientRequest(13, KvCommand("c1", 2, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(13, KvResult.Ok("w")))
    }

    @Test
    fun getWaitsForQuorumAckThenAnswersOnce() {
        val (node, server) = committedLeader()
        val out = server.onClientRequest(ClientRequest(5, KvCommand("c1", 1, KvOp.Get("k"))))
        out.replies() shouldBe emptyList()
        val sends =
            out
                .filterIsInstance<KvServerOutput.NodeEffect>()
                .mapNotNull { (it.effect as? Effect.Send)?.msg }
                .filterIsInstance<AppendEntries>()
        sends.map { it.to }.toSet() shouldBe setOf(n2, n3)
        sends.all { it.readSeq == 1L } shouldBe true
        val fromN3 = AppendEntriesResponse(node.currentTerm, n3, n1, true, 0, 0, 0, 1)
        server.onEffects(node.step(Input.Receive(fromN3))).replies() shouldBe
            listOf(ClientResponse(5, KvResult.Ok(null)))
        val fromN2 = AppendEntriesResponse(node.currentTerm, n2, n1, true, 0, 0, 0, 1)
        server.onEffects(node.step(Input.Receive(fromN2))).replies() shouldBe emptyList()
    }

    @Test
    fun getBeforeCurrentTermCommitAnswersTimeout() {
        val (node, server) = deposedLeader()
        server.onClientRequest(ClientRequest(6, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe
            listOf(ClientResponse(6, KvResult.Timeout))
        node.logSnapshotForChecks().size shouldBe 1
    }

    @Test
    fun pendingGetOnStepDownAnswersNotLeaderOnce() {
        val (node, server) = committedLeader()
        server.onClientRequest(ClientRequest(7, KvCommand("c1", 1, KvOp.Get("k")))).replies() shouldBe emptyList()
        val effects =
            node.step(Input.Receive(AppendEntries(node.currentTerm + 1, n2, n1, 0, 0, emptyList(), 0, 0)))
        server.onEffects(effects).replies() shouldBe listOf(ClientResponse(7, KvResult.NotLeader(null)))
        server.onEffects(listOf(Effect.ReadReady(1, 1))) shouldBe emptyList()
        server.onEffects(listOf(Effect.Rejected(1, Rejection.Unknown))) shouldBe emptyList()
    }

    @Test
    fun readRejectionsMapExhaustively() {
        val (_, server) = committedLeader()
        server.onClientRequest(ClientRequest(42, KvCommand("c1", 1, KvOp.Get("k"))))
        server.onEffects(listOf(Effect.Rejected(1, Rejection.Busy))).replies() shouldBe
            listOf(ClientResponse(42, KvResult.Timeout))
        server.onClientRequest(ClientRequest(43, KvCommand("c1", 2, KvOp.Get("k"))))
        server.onEffects(listOf(Effect.Rejected(2, Rejection.Unknown))).replies() shouldBe
            listOf(ClientResponse(43, KvResult.Timeout))
        server.onClientRequest(ClientRequest(44, KvCommand("c1", 3, KvOp.Get("k"))))
        server.onEffects(listOf(Effect.Rejected(3, Rejection.NotLeader(n3)))).replies() shouldBe
            listOf(ClientResponse(44, KvResult.NotLeader(n3)))
    }
}
