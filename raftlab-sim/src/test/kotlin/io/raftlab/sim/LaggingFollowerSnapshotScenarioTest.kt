package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.SnapshotTaken
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

internal class LaggingFollowerSnapshotScenarioTest {
    private fun runSeed(seed: Long): ScenarioResult = LaggingFollowerSnapshotScenario.scenario.run(seed)

    @Test
    fun passesTwentySeedsWithInstallAndLeaderSnapshot() {
        for (r in LibraryRuns.results(LaggingFollowerSnapshotScenario.NAME)) {
            withClue(r.report) {
                r.passed shouldBe true
                r.violations shouldBe emptyList()
                (r.expectations.all { it.passed }) shouldBe true
                val isolated =
                    r.trace
                        .filterIsInstance<PartitionChanged>()
                        .first { it.time == LaggingFollowerSnapshotScenario.ISOLATE_AT }
                        .groups
                        .first { it.size == 1 }
                        .single()
                (r.trace.any { it is SnapshotInstalled && it.node == isolated }) shouldBe true
                val leaderAtIsolate =
                    r.trace
                        .filterIsInstance<RoleChanged>()
                        .last { it.to == Role.LEADER && it.time <= LaggingFollowerSnapshotScenario.ISOLATE_AT }
                        .node
                (r.trace.any { it is SnapshotTaken && it.node == leaderAtIsolate }) shouldBe true
            }
        }
    }

    @Test
    fun sameSeedSameBytesDifferentSeedDiffers() {
        val a = runSeed(3)
        val b = runSeed(3)
        val c = runSeed(4)
        withClue(a.report) {
            TraceCodec.encode(a.trace) shouldBe TraceCodec.encode(b.trace)
            a.history shouldBe b.history
            a.finalState shouldBe b.finalState
            (TraceCodec.encode(a.trace) != TraceCodec.encode(c.trace)) shouldBe true
        }
    }
}
