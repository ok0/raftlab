package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

class LeaderCrashScenarioTest {
    @Test
    fun leaderCrashRecoversAndKeepsAcknowledgedWrites() {
        val crashAt = LeaderCrashScenario.CRASH_AT
        for (r in LibraryRuns.results("leader-crash")) {
            withClue(r.report) {
                r.expectations.all { it.passed } shouldBe true
                r.violations shouldBe emptyList()

                val crash = r.trace.filterIsInstance<NodeCrashed>().first { it.time == crashAt }
                val leaders = r.trace.filterIsInstance<RoleChanged>()
                val crashedLeader =
                    leaders.last { it.node == crash.node && it.to == Role.LEADER && it.time <= crashAt }
                val crashedTerm = crashedLeader.term
                val newTerm = ScenarioChecks.liveLeaders(r.finalState).maxOfOrNull { it.term } ?: 0L
                (newTerm > crashedTerm) shouldBe true
                ScenarioChecks.acknowledgedWritesSurvive(r.history, r.finalState) shouldBe true

                val writes = r.history.entries.filter { ScenarioChecks.isWrite(it.op) && it.result is KvResult.Ok }
                (writes.any { it.returnedAt != null && it.returnedAt < crashAt }) shouldBe true
                (writes.any { it.returnedAt != null && it.returnedAt > crashAt }) shouldBe true
            }
        }
    }
}
