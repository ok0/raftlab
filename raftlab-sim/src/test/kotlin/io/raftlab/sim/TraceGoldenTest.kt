package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import java.security.MessageDigest

internal class TraceGoldenTest {
    companion object {
        private val NAMES =
            listOf("normal-election", "leader-crash", "partition-heal", "minority-partition", "client-write")
        private val SEEDS = 1L..3L
    }

    @Test
    fun m9LibraryTracesAreUnchanged() {
        val actual: List<String> =
            NAMES.flatMap { name ->
                SEEDS.map { seed ->
                    val r = ScenarioLibrary.all.getValue(name).run(seed)
                    "$name $seed ${sha256(TraceCodec.encode(r.trace))} ${sha256(r.history.toString())}"
                }
            }
        println(actual.joinToString("\n"))
        val expected =
            requireNotNull(javaClass.getResource("/m9-trace-digests.txt"))
                .readText()
                .lines()
                .filter { it.isNotBlank() }
        actual shouldBe expected
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
