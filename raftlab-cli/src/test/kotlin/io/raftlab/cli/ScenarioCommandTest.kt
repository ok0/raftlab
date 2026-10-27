package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.core.Mutation
import io.raftlab.sim.FuzzFailureKind
import io.raftlab.sim.FuzzRunner
import io.raftlab.sim.ScenarioLibrary
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class ScenarioCommandTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun leaderCrashPassesAndWritesTrace() {
        val t = tmp.resolve("trace.jsonl")
        val r = CliTest.run("scenario", "run", "leader-crash", "--seed", "7", "--trace", t.toString())
        r.exitCode shouldBe 0
        r.stdout shouldContain "scenario: leader-crash"
        r.stdout shouldContain "seed: 7"
        r.stdout shouldContain "linearizability: LINEARIZABLE"
        r.stdout shouldContain "result: PASSED"
        val expected =
            ScenarioLibrary.all
                .getValue("leader-crash")
                .run(7)
                .trace
        Files.readString(t) shouldBe TraceCodec.encode(expected)
        TraceCodec.decode(Files.readString(t)) shouldBe expected
    }

    @Test
    fun sameInputsSameOutput() {
        val t1 = tmp.resolve("trace1.jsonl")
        val t2 = tmp.resolve("trace2.jsonl")
        val r1 = CliTest.run("scenario", "run", "leader-crash", "--seed", "7", "--trace", t1.toString())
        val r2 = CliTest.run("scenario", "run", "leader-crash", "--seed", "7", "--trace", t2.toString())
        r1.stdout shouldBe r2.stdout
        Files.readAllBytes(t1) shouldBe Files.readAllBytes(t2)
    }

    @Test
    fun defaultSeedIs42() {
        val r = CliTest.run("scenario", "run", "normal-election")
        r.exitCode shouldBe 0
        r.stdout shouldContain "seed: 42"
    }

    @Test
    fun mutationFailureShowsViolationWithSeed() {
        val failures = FuzzRunner(0L..49L, setOf(Mutation.SKIP_LOG_UP_TO_DATE_CHECK)).run().failures
        val s =
            checkNotNull(failures.firstOrNull { it.kind == FuzzFailureKind.INVARIANT }) {
                "no INVARIANT failure in seeds 0..49"
            }.seed
        val r =
            CliTest.run(
                "scenario",
                "run",
                "fuzz-random",
                "--seed",
                "$s",
                "--mutation",
                "SKIP_LOG_UP_TO_DATE_CHECK",
            )
        r.exitCode shouldBe 1
        r.stdout shouldContain "result: FAILED (INVARIANT)"
        r.stdout shouldContain "violation: invariant '"
        r.stdout shouldContain "seed=$s"
        r.stdout shouldContain "mutations: SKIP_LOG_UP_TO_DATE_CHECK"
    }
}
