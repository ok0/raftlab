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
}
