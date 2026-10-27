package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

internal class MembershipAddRemoveScenarioTest {
    private fun runSeed(seed: Long): ScenarioResult = MembershipAddRemoveScenario.scenario.run(seed)

    @Test
    fun passesTwentySeedsWithSnapshotForJoinedNode() {
        for (r in LibraryRuns.results(MembershipAddRemoveScenario.NAME)) {
            withClue(r.report) {
                r.passed shouldBe true
                r.violations shouldBe emptyList()
                (r.expectations.all { it.passed }) shouldBe true
                (LinearizabilityChecker.check(r) is LinResult.Linearizable) shouldBe true
                (r.trace.any { it is SnapshotInstalled && it.node == NodeId("n4") }) shouldBe true
                r.finalState.size shouldBe 3
                (r.finalState.any { it.id == NodeId("n4") }) shouldBe true
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

    @Test
    fun registeredAfterLaggingFollowerSnapshot() {
        val names = ScenarioLibrary.all.keys.toList()
        names.takeLast(3) shouldBe listOf("lagging-follower-snapshot", "membership-add-remove", "leadership-transfer")
    }
}
