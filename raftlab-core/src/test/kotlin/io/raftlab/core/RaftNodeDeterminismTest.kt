package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.TraceEvent
import org.junit.jupiter.api.Test

class RaftNodeDeterminismTest {
    private fun run(seed: Long): Pair<List<List<Effect>>, List<TraceEvent>> {
        val rec = recorder()
        val node = node(random = SplitMix64(seed), trace = rec)
        val inputs =
            buildList<Input> {
                repeat(40) { add(Input.Tick) }
                add(Input.Receive(rvr(1, n2)))
                repeat(20) { add(Input.Tick) }
                add(Input.Receive(ae(3, n3)))
                repeat(40) { add(Input.Tick) }
                add(Input.Receive(rv(9, n2)))
                repeat(40) { add(Input.Tick) }
            }
        val effects = inputs.map { node.step(it) }
        return effects to rec.events
    }

    private fun ticksUntilSend(
        node: RaftNode,
        cap: Int = 100,
    ): Int {
        for (t in 1..cap) {
            if (node.tick().isNotEmpty()) return t
        }
        error("no send within $cap ticks")
    }

    @Test
    fun sameSeedAndInputsGiveIdenticalOutputs() {
        run(42) shouldBe run(42)
        (run(42).second.isNotEmpty()) shouldBe true
    }

    @Test
    fun differentSeedsGiveDifferentFirstTimeouts() {
        val firstTimeouts =
            (1L..20L).map { seed -> ticksUntilSend(node(random = SplitMix64(seed))) }.toSet()
        (firstTimeouts.size > 1) shouldBe true
    }

    @Test
    fun drawnTimeoutsStayInConfiguredRange() {
        for (seed in 1L..200L) {
            val candidate = node(random = SplitMix64(seed))
            val intervals = List(6) { ticksUntilSend(candidate) }
            (intervals.all { it in 10..20 }) shouldBe true
        }

        val fixed = FixedRandom()
        val fixedNode = node(random = fixed)
        List(6) { ticksUntilSend(fixedNode) }
        (fixed.bounds.isNotEmpty() && fixed.bounds.all { it == 11L }) shouldBe true

        val exact = node(config = RaftConfig(5..5, 1), random = SplitMix64(7))
        val exactIntervals = List(6) { ticksUntilSend(exact) }
        exactIntervals.all { it == 5 } shouldBe true
    }
}
