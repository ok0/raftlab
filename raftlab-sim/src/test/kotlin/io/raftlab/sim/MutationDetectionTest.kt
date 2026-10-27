package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.core.Mutation
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("fuzz")
internal class MutationDetectionTest {
    companion object {
        val SKIP_RANGE: LongRange = 0L..9L
        val CWM_RANGE: LongRange = 0L..9L
        val CPTE_RANGE: LongRange = 0L..99L
        const val CPTE_DETECTED: Boolean = false
        val SAFETY = setOf(FuzzFailureKind.INVARIANT, FuzzFailureKind.LINEARIZABILITY)
    }

    private fun detect(
        m: Mutation,
        range: LongRange,
    ) {
        val report = FuzzRunner(range, setOf(m)).run()
        println(report.render())
        val hit = report.failures.firstOrNull { it.kind in SAFETY }
        withClue(report.render()) { hit shouldNotBe null }
        hit!!
        report.render() shouldContain hit.reproduction
        val fuzz = ScenarioLibrary.all.getValue("fuzz-random")
        FuzzRunner.evaluate(fuzz.run(hit.seed, mutations = setOf(m)))?.kind shouldBe hit.kind
        FuzzRunner.evaluate(fuzz.run(hit.seed)) shouldBe null
    }

    @Test
    fun skipLogUpToDateCheckIsDetected() {
        detect(Mutation.SKIP_LOG_UP_TO_DATE_CHECK, SKIP_RANGE)
    }

    @Test
    fun commitWithoutMajorityIsDetected() {
        detect(Mutation.COMMIT_WITHOUT_MAJORITY, CWM_RANGE)
    }

    @Test
    fun commitPreviousTermEntriesStatusMatchesDocs() {
        val report = FuzzRunner(CPTE_RANGE, setOf(Mutation.COMMIT_PREVIOUS_TERM_ENTRIES)).run()
        report.failures.any { it.kind in SAFETY } shouldBe CPTE_DETECTED
    }

    @Test
    fun docsStateTestedRanges() {
        val text = File(requireNotNull(System.getProperty("raftlab.mutations.doc"))).readText()
        for (m in Mutation.entries) {
            text shouldContain m.name
        }
        for (r in listOf(SKIP_RANGE, CWM_RANGE, CPTE_RANGE)) {
            text shouldContain "seeds ${r.first}..${r.last}"
        }
        text shouldContain "testing only"
        text shouldContain (if (CPTE_DETECTED) "is detected" else "is not detected")
    }
}
