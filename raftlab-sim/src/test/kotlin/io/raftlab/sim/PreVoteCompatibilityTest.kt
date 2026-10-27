package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import java.security.MessageDigest

internal class PreVoteCompatibilityTest {
    companion object {
        private val NAMES =
            listOf(
                "normal-election",
                "leader-crash",
                "partition-heal",
                "minority-partition",
                "client-write",
                "restart-all",
                "lagging-follower-snapshot",
                "membership-add-remove",
            )
        private val SEEDS = 1L..3L
        private val FUZZ_SEEDS = 0L..39L
        const val ELECTION_SEED = 42L
    }

    @Test
    fun preVoteOffMatchesPreM16Traces() {
        val plain = FUZZ_SEEDS.filter { FuzzPlan.draw(it).transfers.isEmpty() }
        (plain.size >= 5) shouldBe true
        val actual: List<String> =
            buildList {
                add(electionLine())
                for (name in NAMES) {
                    for (seed in SEEDS) add(line(name, seed, runScenario(name, seed)))
                }
                for (seed in plain) add(line("fuzz-random", seed, runScenario("fuzz-random", seed)))
            }
        println(actual.joinToString("\n"))
        val expected =
            requireNotNull(javaClass.getResource("/pre-m16-trace-digests.txt"))
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .filter { !it.startsWith("fuzz-random ") || it.split(" ")[1].toLong() in plain }
        actual shouldBe expected
    }

    @Test
    fun defaultPreVoteChangesElectionTrace() {
        val r = runNormalElection(ELECTION_SEED)
        r.trace.filterIsInstance<RoleChanged>().any { it.to == Role.PRE_CANDIDATE } shouldBe true
        val expectedFirst =
            requireNotNull(javaClass.getResource("/pre-m16-trace-digests.txt"))
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .first()
        (electionLine(r) != expectedFirst) shouldBe true
    }

    private fun runScenario(
        name: String,
        seed: Long,
    ): ScenarioResult {
        val scenario = ScenarioLibrary.all.getValue(name)
        return scenario.prepare(seed, preVote = false).execute()
    }

    private fun electionLine(): String =
        electionLine(runNormalElection(ELECTION_SEED, SimOptions(raftConfig = RaftConfig(preVote = false))))

    private fun electionLine(r: ElectionRunResult): String =
        "run-normal-election $ELECTION_SEED ${sha256(TraceCodec.encode(r.trace))} -"

    private fun line(
        name: String,
        seed: Long,
        r: ScenarioResult,
    ) = "$name $seed ${sha256(TraceCodec.encode(r.trace))} ${sha256(r.history.toString())}"

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
