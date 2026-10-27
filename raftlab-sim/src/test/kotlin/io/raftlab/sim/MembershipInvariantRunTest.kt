package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotTaken
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

internal class MembershipInvariantRunTest {
    private fun script(
        seed: Long,
        checker: InvariantChecker,
    ): Simulation {
        val sim =
            Simulation(
                seed,
                SimOptions(nodeCount = 3, raftConfig = RaftConfig(snapshotThreshold = 5)),
                checker,
            )
        for (t in 800L..5000L step 20L) {
            sim.at(t) { it.propose("c$t".encodeToByteArray()) }
        }
        sim.at(1000) { it.addNode() }
        sim.at(1500) { it.crash(NodeId("n2")) }
        sim.at(1800) { it.restart(NodeId("n2")) }
        sim.at(2500) { s ->
            val leader = s.nodes().filter { it.up }.maxByOrNull { it.currentTerm }
            leader?.let { s.removeNode(it.id) }
        }
        var victim: NodeId? = null
        sim.at(3200) { s ->
            s.nodes().firstOrNull { it.up && it.role != Role.LEADER }?.let {
                victim = it.id
                s.crash(it.id)
            }
        }
        sim.at(3500) { s -> victim?.let { s.restart(it) } }
        sim.runUntil(7000)
        return sim
    }

    @Test
    fun noViolationsAcrossChanges() {
        val runs = (1L..5L).map { seed -> script(seed, InvariantChecker.standard()) }
        runs.forEach { it.violations shouldBe emptyList() }
        (runs.any { sim -> sim.trace.any { it is LogAppended && it.entryKind == EntryKind.CONFIG } }) shouldBe true
        (runs.any { sim -> sim.trace.any { it is SnapshotTaken } }) shouldBe true
        (
            runs.any { sim ->
                sim.trace.any { it is RoleChanged && it.reason == "removed from configuration" }
            }
        ) shouldBe true
    }

    @Test
    fun checkerDoesNotAlterTraceOrDraws() {
        val checked = script(3, InvariantChecker.standard())
        val unchecked = script(3, InvariantChecker.none())
        TraceCodec.encode(checked.trace) shouldBe TraceCodec.encode(unchecked.trace)
    }
}
