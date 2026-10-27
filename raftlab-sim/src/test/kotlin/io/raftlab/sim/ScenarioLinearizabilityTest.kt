package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import org.junit.jupiter.api.Test

class ScenarioLinearizabilityTest {
    @Test
    fun libraryScenariosAreLinearizableForTwentySeeds() {
        for (name in ScenarioLibrary.all.keys) {
            for (r in LibraryRuns.results(name)) {
                val lin = LinearizabilityChecker.check(r)
                withClue(LinearizabilityChecker.render(lin, r.scenario, r.seed)) {
                    (lin is LinResult.Linearizable) shouldBe true
                }
                if (r.history.entries.isEmpty()) {
                    lin shouldBe LinResult.Linearizable(0)
                }
            }
        }
    }

    @Test
    fun requireLinearizableAcceptsLibraryRuns() {
        for (name in ScenarioLibrary.all.keys) {
            for (r in LibraryRuns.results(name)) {
                LinearizabilityChecker.requireLinearizable(r)
            }
        }
    }

    @Test
    fun corruptedScenarioHistoryIsRejected() {
        val r = LibraryRuns.results("client-write").first()
        val getIndex = r.history.entries.indexOfFirst { !it.pending && it.op is KvOp.Get }
        val i = if (getIndex >= 0) getIndex else r.history.entries.indexOfFirst { !it.pending }
        withClue("no completed operation to corrupt in seed ${r.seed}") {
            (i >= 0) shouldBe true
        }
        val target = r.history.entries[i]
        val corrupted: KvResult =
            if (target.op is KvOp.Get) {
                KvResult.Ok("corrupted")
            } else {
                KvResult.CasFailed("corrupted")
            }
        val entries = r.history.entries.mapIndexed { j, e -> if (j == i) e.copy(result = corrupted) else e }
        val bad = r.copy(history = History(entries))
        (LinearizabilityChecker.check(bad) as LinResult.NotLinearizable).key shouldBe target.op.key
        r.passed shouldBe true
        bad.passed shouldBe true
        val thrown = shouldThrow<IllegalStateException> { LinearizabilityChecker.requireLinearizable(bad) }
        thrown.message!! shouldContain "scenario 'client-write' seed ${r.seed}"
    }
}
