package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.trace.DropReason
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageDuplicated
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.SnapshotTaken
import io.raftlab.trace.TraceEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

internal class FuzzScenarioTest {
    companion object {
        private val SEEDS = 0L..99L
    }

    @Test
    fun planIsDeterministicAndSeedDependent() {
        FuzzPlan.draw(5) shouldBe FuzzPlan.draw(5)
        ((0L..19L).map { FuzzPlan.draw(it) }.toSet().size > 1) shouldBe true
    }

    @Test
    fun plansCoverTheFaultMix() {
        val plans = SEEDS.map { FuzzPlan.draw(it) }
        (plans.map { it.nodes }.toSet() == setOf(3, 5)) shouldBe true
        (plans.any { it.faults.dropRate > 0 }) shouldBe true
        (plans.any { it.faults.duplicateRate > 0 }) shouldBe true
        (plans.any { it.faults.reorder }) shouldBe true
        (plans.any { it.partitions.isNotEmpty() }) shouldBe true
        (plans.any { p -> p.partitions.any { it.isolateLeader } }) shouldBe true
        (plans.any { p -> p.crashes.any { it.leader } }) shouldBe true
        (plans.any { p -> p.crashes.any { !it.leader } }) shouldBe true
        (
            plans.any { p ->
                p.partitions.any { pw -> p.crashes.any { c -> pw.start < c.end && c.start < pw.end } }
            }
        ) shouldBe true
        (plans.any { it.clientFaults.enabled }) shouldBe true
        plans.all { p ->
            (p.partitions.map { it.end } + p.crashes.map { it.end }).all { it <= FuzzScenario.FAULT_UNTIL }
        } shouldBe true
        plans.all { p -> p.partitions.zipWithNext().all { (a, b) -> a.end < b.start } } shouldBe true
        plans.all { it.clientCount * it.opsPerClient <= 200 } shouldBe true
        (plans.any { it.snapshotThreshold <= 8 }) shouldBe true
        (plans.any { it.snapshotThreshold == RaftConfig.DEFAULT_SNAPSHOT_THRESHOLD }) shouldBe true
    }

    @Tag("fuzz")
    @Test
    fun runsCoverFaultsInTraces() {
        val fuzz = ScenarioLibrary.all.getValue("fuzz-random")
        val executed =
            SEEDS.map { seed ->
                val run = fuzz.prepare(seed)
                val r = run.execute()
                withClue(r.report) {
                    r.passed shouldBe true
                    r.finalState.all { it.up } shouldBe true
                    val lastPartition = r.trace.filterIsInstance<PartitionChanged>().lastOrNull()
                    lastPartition?.let { it.groups.size shouldBe 1 }
                }
                run to r
            }
        val results = executed.map { it.second }
        (results.any { r -> r.trace.any { it is MessageDropped && it.reason == DropReason.RANDOM } }) shouldBe true
        (results.any { r -> r.trace.any { it is MessageDuplicated } }) shouldBe true
        (results.any { r -> r.trace.any { it is PartitionChanged && it.groups.size > 1 } }) shouldBe true
        (results.any { r -> r.trace.any { it is NodeCrashed } }) shouldBe true
        (results.any { r -> r.trace.any { it is NodeRestarted } }) shouldBe true
        (results.any { r -> crashesLeader(r.trace) }) shouldBe true
        (executed.any { (run, _) -> run.clients.any { it.attempts.size > it.ops.size } }) shouldBe true
        val sizes = results.map { it.finalState.size }.toSet()
        (sizes.contains(3) && sizes.contains(5)) shouldBe true
        val takenSeeds = results.count { r -> r.trace.any { it is SnapshotTaken } }
        val installedSeeds = results.count { r -> r.trace.any { it is SnapshotInstalled } }
        val restartedAfterSnapshotSeeds = results.count { r -> restartsAfterSnapshot(r.trace) }
        println(
            "fuzz snapshot coverage: SnapshotTaken seeds=$takenSeeds, SnapshotInstalled seeds=$installedSeeds, " +
                "restart after snapshot seeds=$restartedAfterSnapshotSeeds",
        )
        (takenSeeds >= 10) shouldBe true
        (installedSeeds >= 3) shouldBe true
        (restartedAfterSnapshotSeeds >= 1) shouldBe true
    }

    private fun restartsAfterSnapshot(trace: List<TraceEvent>): Boolean =
        trace.indices.any { i ->
            val restart = trace[i]
            restart is NodeRestarted &&
                trace.subList(0, i).any { e ->
                    (e is SnapshotTaken || e is SnapshotInstalled) && e.node == restart.node
                }
        }

    private fun crashesLeader(trace: List<TraceEvent>): Boolean =
        trace.indices.any { i ->
            val crash = trace[i]
            crash is NodeCrashed &&
                (trace.subList(0, i).lastOrNull { it is RoleChanged && it.node == crash.node } as? RoleChanged)
                    ?.to == Role.LEADER
        }
}
