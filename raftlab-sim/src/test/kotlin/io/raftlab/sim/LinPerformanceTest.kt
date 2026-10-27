package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.kv.KvResult
import org.junit.jupiter.api.Test

class LinPerformanceTest {
    private val histories = (1L..20L).map { LinHistories.generate(it) }
    private val stateProxy = 20_000L

    private fun lin(h: History) = LinearizabilityChecker.check(h)

    @Test
    fun generatedHistoriesHaveTargetShape() {
        histories.forEachIndexed { i, h ->
            withClue("seed ${i + 1}") {
                h.entries.size shouldBe 200
                val keys = h.entries.map { it.op.key }.toSortedSet()
                keys.size shouldBe 5
                h.pending().size shouldBe 2
            }
        }
    }

    @Test
    fun generatedHistoriesAreLinearizableWithinStateProxy() {
        histories.forEachIndexed { i, h ->
            val r = lin(h)
            withClue("seed ${i + 1}: " + LinearizabilityChecker.render(r)) {
                (r is LinResult.Linearizable) shouldBe true
                ((r as LinResult.Linearizable).explored <= stateProxy) shouldBe true
            }
        }
    }

    @Test
    fun checksFinishUnder50msAfterWarmup() {
        repeat(3) { histories.forEach { lin(it) } }
        histories.forEachIndexed { i, h ->
            val times =
                List(5) {
                    val t0 = System.nanoTime()
                    lin(h)
                    System.nanoTime() - t0
                }
            val median = times.sorted()[2]
            val ms = median / 1_000_000L
            withClue("seed ${i + 1} median $ms ms") {
                (median < 50_000_000L) shouldBe true
            }
        }
    }

    @Test
    fun corruptedReadIsRejected() {
        val c = LinHistories.corruptFirstRead(histories[0])
        val r = lin(c)
        withClue(LinearizabilityChecker.render(r)) {
            (r is LinResult.NotLinearizable) shouldBe true
        }
        val corrupted = c.entries.first { it.result == KvResult.Ok("corrupted") }
        val key = corrupted.op.key
        (r as LinResult.NotLinearizable).key shouldBe key
    }

    @Test
    fun repeatedChecksAreIdentical() {
        histories.forEachIndexed { i, h ->
            withClue("seed ${i + 1}") {
                lin(h) shouldBe lin(h)
                LinearizabilityChecker.render(lin(h)) shouldBe LinearizabilityChecker.render(lin(h))
            }
        }
    }

    @Test
    fun shuffledInputOrderGivesSameResult() {
        histories.forEachIndexed { i, h ->
            for (base in listOf(h, LinHistories.corruptFirstRead(h))) {
                val odd = base.entries.filterIndexed { j, _ -> j % 2 == 1 }
                val even = base.entries.filterIndexed { j, _ -> j % 2 == 0 }
                val perms = listOf(base.entries.reversed(), odd + even)
                for (perm in perms) {
                    withClue("seed ${i + 1}") {
                        lin(History(perm)) shouldBe lin(base)
                    }
                }
            }
        }
    }
}
