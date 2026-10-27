package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.core.Mutation
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import java.util.TreeMap

internal class FuzzRunnerTest {
    companion object {
        private val EVEN_FAILS =
            scenario("fuzz-random") {
                nodes(3)
                runFor(200)
                expect("odd seed") { it.seed % 2L != 0L }
            }
    }

    @Test
    fun cleanRangeSaysSo() {
        val report = FuzzRunner(0L..2L).run()
        report.seedsRun shouldBe 3
        report.passed shouldBe 3
        report.failed shouldBe 0
        report.firstFailingSeed shouldBe null
        val lines = report.render().lines()
        lines.first() shouldBe "fuzz 'fuzz-random' seeds 0..2, mutations: none"
        lines.last() shouldBe "summary: 3 seeds run, 3 passed, 0 failed (no failures)"
    }

    @Test
    fun forcedFailuresAreListedWithReproduction() {
        val m = setOf(Mutation.COMMIT_PREVIOUS_TERM_ENTRIES, Mutation.SKIP_LOG_UP_TO_DATE_CHECK)
        val captured = TreeMap<Long, ScenarioResult>()
        val report = FuzzRunner(1L..6L, m, EVEN_FAILS) { captured[it.seed] = it }.run()
        report.failures.map { it.seed } shouldBe listOf(2L, 4L, 6L)
        report.failures.forEach { f ->
            f.kind shouldBe FuzzFailureKind.EXPECTATION
            f.detail shouldBe "expectation failed: odd seed"
        }
        report.failures[0].reproduction shouldBe
            "raftlab scenario run fuzz-random --seed 2 --mutation SKIP_LOG_UP_TO_DATE_CHECK " +
            "--mutation COMMIT_PREVIOUS_TERM_ENTRIES"
        val text = report.render()
        text shouldContain "  repro: " + report.failures[0].reproduction
        text shouldContain "mutations: SKIP_LOG_UP_TO_DATE_CHECK, COMMIT_PREVIOUS_TERM_ENTRIES"
        text shouldContain "summary: 6 seeds run, 3 passed, 3 failed, first failing seed 2"
        report.firstFailingSeed shouldBe 2L

        val again = TreeMap<Long, ScenarioResult>()
        FuzzRunner(1L..6L, m, EVEN_FAILS) { again[it.seed] = it }.run().render() shouldBe text

        EVEN_FAILS.run(4, mutations = m).let { r ->
            TraceCodec.encode(r.trace) shouldBe TraceCodec.encode(captured.getValue(4L).trace)
            FuzzRunner.evaluate(r)?.kind shouldBe FuzzFailureKind.EXPECTATION
            r.mutations shouldBe m
            r.report shouldContain "mutations: SKIP_LOG_UP_TO_DATE_CHECK, COMMIT_PREVIOUS_TERM_ENTRIES"
        }
    }

    @Test
    fun evaluateClassifies() {
        val r = LibraryRuns.results("client-write").first()
        FuzzRunner.evaluate(r) shouldBe null

        val invariant = r.copy(violations = listOf(Violation("LogMatching", 1, 5, "x")))
        val inv = FuzzRunner.evaluate(invariant)
        inv?.kind shouldBe FuzzFailureKind.INVARIANT
        (inv?.detail?.startsWith("LogMatching at t=5") == true) shouldBe true

        FuzzRunner.evaluate(r.copy(error = "boom"))?.kind shouldBe FuzzFailureKind.RUN_ERROR

        val getIndex = r.history.entries.indexOfFirst { !it.pending && it.op is KvOp.Get }
        val i = if (getIndex >= 0) getIndex else r.history.entries.indexOfFirst { !it.pending }
        val target = r.history.entries[i]
        val corrupted: KvResult =
            if (target.op is KvOp.Get) {
                KvResult.Ok("corrupted")
            } else {
                KvResult.CasFailed("corrupted")
            }
        val entries = r.history.entries.mapIndexed { j, e -> if (j == i) e.copy(result = corrupted) else e }
        val bad = r.copy(history = History(entries))
        FuzzRunner.evaluate(bad)?.kind shouldBe FuzzFailureKind.LINEARIZABILITY
    }

    @Test
    fun rejectsEmptyRange() {
        shouldThrow<IllegalArgumentException> { FuzzRunner(5L..4L) }
    }
}
