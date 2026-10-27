package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import java.security.MessageDigest

internal class M13TraceGoldenTest {
    companion object {
        private val NAMES = listOf("restart-all", "lagging-follower-snapshot")
        private val SEEDS = 1L..3L
        private val FUZZ_SEEDS = 0L..19L
    }

    @Test
    fun m13LibraryTracesAreUnchanged() {
        val named: List<String> =
            NAMES.flatMap { name ->
                SEEDS.map { seed ->
                    val r = ScenarioLibrary.all.getValue(name).run(seed)
                    "$name $seed ${sha256(TraceCodec.encode(r.trace))} ${sha256(r.history.toString())}"
                }
            }
        val plainSeeds = FUZZ_SEEDS.filter { FuzzPlan.draw(it).memberships.isEmpty() }.toSet()
        (plainSeeds.size >= 3) shouldBe true
        val fuzz: List<String> =
            plainSeeds.sorted().map { seed ->
                val r = ScenarioLibrary.all.getValue("fuzz-random").run(seed)
                "fuzz-random $seed ${sha256(TraceCodec.encode(r.trace))} ${sha256(r.history.toString())}"
            }
        val actual = named + fuzz
        println(actual.joinToString("\n"))
        val expected =
            requireNotNull(javaClass.getResource("/m13-trace-digests.txt"))
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .filter { line ->
                    val parts = line.split(" ")
                    parts[0] != "fuzz-random" || parts[1].toLong() in plainSeeds
                }
        actual shouldBe expected
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
