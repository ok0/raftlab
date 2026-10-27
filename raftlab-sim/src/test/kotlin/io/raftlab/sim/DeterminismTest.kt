package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

class DeterminismTest {
    @Test
    fun sameSeedSameBytesNormalElection() {
        for (seed in 1L..5L) {
            TraceCodec.encode(runNormalElection(seed).trace) shouldBe TraceCodec.encode(runNormalElection(seed).trace)
        }
        TraceCodec.encode(runNormalElection(1).trace) shouldNotBe TraceCodec.encode(runNormalElection(2).trace)
    }

    @Test
    fun sameSeedSameBytesWithAllFaults() {
        val opts = SimOptions(faults = NetworkFaults(1, 30, 100, 100, true))
        val first = Simulation(7, opts)
        first.runUntil(3000)
        val second = Simulation(7, opts)
        second.runUntil(3000)
        TraceCodec.encode(first.trace) shouldBe TraceCodec.encode(second.trace)

        val other = Simulation(8, opts)
        other.runUntil(3000)
        TraceCodec.encode(first.trace) shouldNotBe TraceCodec.encode(other.trace)
    }

    @Test
    fun sameSeedSameBytesWithCrashRestart() {
        fun run(): String {
            val sim = Simulation(7)
            sim.at(300) { it.crash(n1) }
            sim.at(800) { it.restart(n1) }
            sim.at(1200) { it.crash(n3) }
            sim.runUntil(3000)
            return TraceCodec.encode(sim.trace)
        }
        run() shouldBe run()
    }

    @Test
    fun networkFaultsDoNotChangeNodeDraws() {
        val fdPlain = FirstDraws()
        val plain = Simulation(42, SimOptions(), InvariantChecker.standard(), fdPlain.factory())
        plain.runUntil(0)

        val fdFaulty = FirstDraws()
        val faulty =
            Simulation(
                42,
                SimOptions(faults = NetworkFaults(0, 50, 500, 500, true)),
                InvariantChecker.standard(),
                fdFaulty.factory(),
            )
        faulty.runUntil(0)

        for (id in listOf(n1, n2, n3, n4, n5)) {
            fdPlain.byNode[id]!!.draws.first() shouldBe fdFaulty.byNode[id]!!.draws.first()
        }
        val n1Before = fdPlain.byNode[n1]!!.draws.first()

        plain.runUntil(300)
        faulty.runUntil(300)

        fdPlain.byNode[n1]!!.draws.first() shouldBe n1Before
        fdFaulty.byNode[n1]!!.draws.first() shouldBe n1Before
        fdPlain.byNode[n1]!!.draws.first() shouldBe fdFaulty.byNode[n1]!!.draws.first()
    }
}
