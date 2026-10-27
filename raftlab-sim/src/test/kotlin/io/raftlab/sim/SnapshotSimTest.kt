package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.Role
import io.raftlab.trace.SnapshotTaken
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

internal class SnapshotSimTest {
    @Test
    fun crashedNodeWithCompactedStorageIsObservable() {
        val sim = Simulation(1, SimOptions(nodeCount = 3, raftConfig = RaftConfig(snapshotThreshold = 2)))
        sim.runUntil(2_000) { s -> s.nodes().any { it.role == Role.LEADER } }
        for (i in 1..10) {
            sim.propose(byteArrayOf(i.toByte()))
            sim.runFor(50)
        }
        val id = sim.nodes().first { it.role == Role.FOLLOWER && it.snapshotIndex > 0L }.id
        val covered = sim.node(id).snapshotIndex
        sim.crash(id)
        val down = sim.node(id)
        down.up shouldBe false
        (down.snapshotIndex > 0L) shouldBe true
        (down.log.firstOrNull()?.index ?: (down.snapshotIndex + 1)) shouldBe down.snapshotIndex + 1
        sim.restart(id)
        sim.runFor(1_000)
        (sim.node(id).commitIndex >= covered) shouldBe true
        (sim.trace.any { it is SnapshotTaken }) shouldBe true
        sim.violations shouldBe emptyList()
    }

    @Test
    fun fileStorageRestartRestoresSnapshotData() {
        val snapshotRestart =
            scenario("snapshot-restart") {
                var views: List<NodeView> = emptyList()
                nodes(3)
                storage(StorageKind.Durable(FileStorageProvider()))
                snapshotThreshold(3)
                every(30, 500, 770) { leader()?.let { submitTo(it.id, KvOp.Put("k$now", "v$now")) } }
                at(1_500) { nodeIds.forEach { crash(it) } }
                at(1_700) { nodeIds.forEach { restart(it) } }
                at(2_700) { views = nodes() }
                runFor(3_500)
                expect("all writes acknowledged before the crash") { v ->
                    v.history.entries.size == 10 &&
                        v.history.entries.all { it.result is KvResult.Ok && (it.returnedAt ?: Long.MAX_VALUE) < 1_500 }
                }
                expect("no client op invoked after the crash") { v ->
                    v.history.entries.none { it.invokedAt > 1_500 }
                }
                expect("every node restored snapshot data") { v ->
                    val writes =
                        v.history.entries
                            .map { it.op }
                            .filterIsInstance<KvOp.Put>()
                    views.size == 3 &&
                        views.all { n ->
                            n.up && n.snapshotIndex >= 1 && writes.all { w -> n.kvData[w.key] == w.value }
                        }
                }
            }
        for (seed in 1L..3L) {
            val r = snapshotRestart.run(seed)
            withClue(r.report) {
                r.passed shouldBe true
                r.violations shouldBe emptyList()
                val restarted =
                    r.trace
                        .filterIsInstance<NodeRestarted>()
                        .map { it.node }
                        .toSet()
                restarted shouldBe setOf(n1, n2, n3)
            }
        }
    }

    @Test
    fun checkerOnOrOffGivesSameTraceAndHistory() {
        val on = LaggingFollowerSnapshotScenario.scenario.prepare(5).execute()
        val off = LaggingFollowerSnapshotScenario.scenario.prepare(5, checker = InvariantChecker.none()).execute()
        TraceCodec.encode(on.trace) shouldBe TraceCodec.encode(off.trace)
        on.history shouldBe off.history
    }

    @Test
    fun snapshotThresholdMustBePositive() {
        shouldThrow<IllegalArgumentException> {
            scenario("t") {
                snapshotThreshold(0)
                runFor(1)
            }
        }
    }
}
