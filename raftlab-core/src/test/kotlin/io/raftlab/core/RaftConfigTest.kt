package io.raftlab.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RaftConfigTest {
    @Test
    fun defaultsAreTenToTwentyTicksAndHeartbeatThree() {
        RaftConfig().electionTimeoutTicks shouldBe 10..20
        RaftConfig().heartbeatTicks shouldBe 3
    }

    @Test
    fun rejectsEmptyRange() {
        shouldThrow<IllegalArgumentException> { RaftConfig(5..4) }
    }

    @Test
    fun rejectsNonPositiveRange() {
        shouldThrow<IllegalArgumentException> { RaftConfig(0..5, 1) }
        shouldThrow<IllegalArgumentException> { RaftConfig(-3..5, 1) }
    }

    @Test
    fun rejectsHeartbeatNotBelowMinimumTimeout() {
        shouldThrow<IllegalArgumentException> { RaftConfig(10..20, 10) }
        shouldThrow<IllegalArgumentException> { RaftConfig(10..20, 11) }
        shouldThrow<IllegalArgumentException> { RaftConfig(10..20, 0) }
    }

    @Test
    fun acceptsSingletonRange() {
        RaftConfig(2..2, 1).electionTimeoutTicks shouldBe 2..2
    }

    @Test
    fun defaultMaxEntriesPerAppendIs64() {
        RaftConfig().maxEntriesPerAppend shouldBe 64
    }

    @Test
    fun mutationsDefaultToEmpty() {
        RaftConfig().mutations shouldBe emptySet()
        RaftConfig(mutations = setOf(Mutation.COMMIT_WITHOUT_MAJORITY)).mutations shouldBe
            setOf(Mutation.COMMIT_WITHOUT_MAJORITY)
    }

    @Test
    fun rejectsMaxEntriesPerAppendBelowOne() {
        shouldThrow<IllegalArgumentException> { RaftConfig(maxEntriesPerAppend = 0) }
        shouldThrow<IllegalArgumentException> { RaftConfig(maxEntriesPerAppend = -1) }
        RaftConfig(maxEntriesPerAppend = 1).maxEntriesPerAppend shouldBe 1
    }

    @Test
    fun defaultSnapshotThresholdIs1000() {
        RaftConfig().snapshotThreshold shouldBe 1000
        RaftConfig.DEFAULT_SNAPSHOT_THRESHOLD shouldBe 1000
    }

    @Test
    fun rejectsSnapshotThresholdBelowOne() {
        shouldThrow<IllegalArgumentException> { RaftConfig(snapshotThreshold = 0) }
        shouldThrow<IllegalArgumentException> { RaftConfig(snapshotThreshold = -5) }
        RaftConfig(snapshotThreshold = 1).snapshotThreshold shouldBe 1
    }
}
