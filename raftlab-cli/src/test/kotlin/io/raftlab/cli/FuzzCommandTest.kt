package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.text.RegexOption

internal class FuzzCommandTest {
    @Test
    fun mutationSweepFailsAndReproduces() {
        val first = CliTest.run("fuzz", "--seeds", "50", "--mutation", "SKIP_LOG_UP_TO_DATE_CHECK")
        first.exitCode shouldBe 1
        val second = CliTest.run("fuzz", "--seeds", "50", "--mutation", "SKIP_LOG_UP_TO_DATE_CHECK")
        second.stdout shouldBe first.stdout

        val match =
            Regex("""^seed (\d+): ([A-Z_]+):""", RegexOption.MULTILINE).find(first.stdout)
                ?: error("no failing seed line in fuzz output:\n${first.stdout}")
        val seed = match.groupValues[1]
        val kind = match.groupValues[2]

        val reproLine = "  repro: raftlab scenario run fuzz-random --seed $seed --mutation SKIP_LOG_UP_TO_DATE_CHECK"
        first.stdout shouldContain reproLine

        val repro =
            CliTest.run(
                *reproLine
                    .removePrefix("  repro: ")
                    .split(" ")
                    .drop(1)
                    .toTypedArray(),
            )
        repro.exitCode shouldBe 1
        repro.stdout shouldContain "result: FAILED ($kind)"

        CliTest.run("scenario", "run", "fuzz-random", "--seed", seed).exitCode shouldBe 0
    }

    @Test
    fun cleanSweepPasses() {
        val first = CliTest.run("fuzz", "--seeds", "20")
        first.exitCode shouldBe 0
        first.stdout shouldContain "(no failures)"
        val second = CliTest.run("fuzz", "--seeds", "20")
        second.stdout shouldBe first.stdout
    }
}
