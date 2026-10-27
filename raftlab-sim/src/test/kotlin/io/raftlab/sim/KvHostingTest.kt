package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import org.junit.jupiter.api.Test

fun kvSim(
    seed: Long,
    nodes: Int = 3,
) = Simulation(seed, SimOptions(nodeCount = nodes), InvariantChecker.standard(), NodeFactory.RAFT, hostKv = true)

class RecordingEndpoint : ClientEndpoint {
    val got = ArrayList<ClientResponse>()

    override fun replyDelay() = 1L

    override fun receive(response: ClientResponse) {
        got += response
    }
}

class KvHostingTest {
    @Test
    fun putThroughLeaderReachesEveryNode() {
        val sim = kvSim(1)
        sim.runUntil(5000) { it.leader() != null }
        val leader = checkNotNull(sim.leader()).id
        val ep = RecordingEndpoint()
        sim.clientSend(leader, ClientRequest(1, KvCommand("t", 1, KvOp.Put("k", "v"))), 1, ep)
        sim.runFor(200)

        ep.got shouldBe listOf(ClientResponse(1, KvResult.Ok(null)))
        for (id in sim.nodeIds) {
            sim.kvData(id) shouldBe mapOf("k" to "v")
        }
        sim.violations shouldBe emptyList()
    }

    @Test
    fun restartRebuildsKvFromLog() {
        val sim = kvSim(1)
        sim.runUntil(5000) { it.leader() != null }
        val leader = checkNotNull(sim.leader()).id
        val ep = RecordingEndpoint()
        sim.clientSend(leader, ClientRequest(1, KvCommand("t", 1, KvOp.Put("k", "v"))), 1, ep)
        sim.runFor(200)

        val follower = sim.nodeIds.first { it != leader }
        sim.crash(follower)
        sim.kvData(follower) shouldBe null
        sim.restart(follower)
        sim.kvData(follower) shouldBe emptyMap()
        sim.runFor(300)
        sim.kvData(follower) shouldBe mapOf("k" to "v")
        sim.violations shouldBe emptyList()
    }

    @Test
    fun pendingRequestOnCrashedNodeGetsNoResponse() {
        val sim = kvSim(4)
        sim.runUntil(5000) { it.leader() != null }
        val l = checkNotNull(sim.leader()).id
        val others = sim.nodeIds.filter { it != l }.toSet()
        sim.partition(setOf(l), others)
        val ep = RecordingEndpoint()
        val cmd = KvCommand("t", 1, KvOp.Put("k", "v"))
        sim.clientSend(l, ClientRequest(1, cmd), 1, ep)
        sim.runFor(20)

        (ScenarioChecks.commandsOf(sim.node(l).log, "t").isNotEmpty()) shouldBe true
        sim.crash(l)
        sim.heal()
        sim.runUntil(sim.now + 2_000) { it.leader() != null }
        sim.runFor(50)
        ep.got.size shouldBe 0

        val newLeader = checkNotNull(sim.leader()).id
        sim.clientSend(newLeader, ClientRequest(2, cmd), 1, ep)
        sim.runFor(200)

        ep.got shouldBe listOf(ClientResponse(2, KvResult.Ok(null)))
        for (id in sim.nodeIds) {
            if (sim.node(id).up) sim.kvData(id) shouldBe mapOf("k" to "v")
        }
    }

    @Test
    fun recordingModeUnchanged() {
        Simulation(1).kvData(n1) shouldBe null
        val ep = RecordingEndpoint()
        val request = ClientRequest(1, KvCommand("t", 1, KvOp.Put("k", "v")))
        shouldThrow<IllegalStateException> { Simulation(1).clientSend(n1, request, 1, ep) }
        shouldThrow<IllegalStateException> { kvSim(1).propose(byteArrayOf(1)) }
        shouldThrow<IllegalArgumentException> {
            Simulation(1, SimOptions(), InvariantChecker.standard(), forcedLeaderFactory, hostKv = true)
        }
    }
}
