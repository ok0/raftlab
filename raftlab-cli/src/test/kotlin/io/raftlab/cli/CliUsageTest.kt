package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.raftlab.sim.ScenarioLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class CliUsageTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun rootHelpListsCommands() {
        val r = CliTest.run("--help")
        r.exitCode shouldBe 0
        for (name in listOf("scenario", "export", "docs", "fuzz", "node", "kv")) {
            r.stdout shouldContain name
        }
        r.stdout shouldContain "--version"
    }

    @Test
    fun commandHelpListsOptions() {
        val cases =
            listOf(
                listOf("scenario", "run", "--help") to listOf("--seed", "--mutation", "--trace"),
                listOf("export", "--help") to
                    listOf(
                        "--trace",
                        "--scenario",
                        "--seed",
                        "--format",
                        "--from",
                        "--to",
                        "--nodes",
                        "--at",
                        "--out",
                    ),
                listOf("docs", "--help") to
                    listOf(
                        "--check",
                        "--out",
                        "docs/generated",
                        "build/raftlab/module-graph.json",
                        "README.md",
                    ),
                listOf("fuzz", "--help") to listOf("--seeds", "--start", "--mutation"),
                listOf("node", "--help") to listOf("--id", "--cluster", "--data-dir", "--trace", "--tick-millis"),
                listOf("kv", "--help") to listOf("--cluster", "--timeout-millis", "put", "get", "delete", "cas"),
                listOf("kv", "cas", "--help") to listOf("--expect-absent", "--new-absent"),
            )
        for ((args, expected) in cases) {
            val r = CliTest.run(*args.toTypedArray())
            r.exitCode shouldBe 0
            for (text in expected) {
                r.stdout shouldContain text
            }
        }
    }

    @Test
    fun usageErrorsExitTwo() {
        val missingData = tmp.resolve("d").toString()
        val cases =
            listOf(
                arrayOf("bogus"),
                arrayOf("scenario", "list", "--bogus"),
                arrayOf("scenario", "run", "no-such"),
                arrayOf("scenario", "run", "leader-crash", "--mutation", "NOPE"),
                arrayOf("fuzz", "--mutation", "NOPE"),
                arrayOf("fuzz", "--seeds", "0"),
                arrayOf("fuzz", "--start", "-1"),
                arrayOf("kv", "--cluster", "n1=localhost", "get", "k"),
                arrayOf("node", "--id", "n1", "--cluster", "garbage", "--data-dir", missingData),
                arrayOf("node", "--cluster", "n1=127.0.0.1:7001", "--data-dir", missingData),
                arrayOf("kv", "get", "k"),
                arrayOf("export", "--format", "state"),
                arrayOf("scenario"),
            )
        for (args in cases) {
            val r = CliTest.run(*args)
            r.exitCode shouldBe 2
            if (args.contentEquals(arrayOf("scenario"))) continue
            val lines = r.stderr.lines().filter { it.isNotBlank() }
            lines.size shouldBe 1
            lines.single() shouldStartWith "error:"
            r.stderr shouldNotContain "Exception"
            r.stderr shouldNotContain "\tat "
        }
    }

    @Test
    fun scenarioListMatchesLibrary() {
        val r = CliTest.run("scenario", "list")
        r.exitCode shouldBe 0
        r.stdout.lines().filter { it.isNotEmpty() } shouldBe ScenarioLibrary.all.keys.toList()
        r.stdout shouldContain "fuzz-random"
        r.stdout shouldContain "restart-all"
    }

    @Test
    fun defaultsAreDocumented() {
        CliDefaults.FUZZ_SEEDS shouldBe 1000L
        CliDefaults.FUZZ_START shouldBe 0L
        CliDefaults.SEED shouldBe 42L
        (CliDefaults.KV_TIMEOUT_MILLIS <= 30_000L) shouldBe true
    }
}
