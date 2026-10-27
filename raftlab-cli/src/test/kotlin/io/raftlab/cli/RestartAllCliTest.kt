package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.raftlab.sim.DurableStorageProvider
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class RestartAllCliTest {
    @Test
    fun cliProviderIsDiscovered() {
        DurableStorageProvider.discover().shouldBeInstanceOf<CliFileStorageProvider>()
    }

    @Test
    fun restartAllPassesFiveSeedsAndCleansUp() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))

        fun runDirs(): List<String> =
            Files.list(tmp).use { stream ->
                stream
                    .map { it.fileName.toString() }
                    .filter { it.startsWith("raftlab-run-") }
                    .toList()
                    .sorted()
            }
        val before = runDirs()
        for (seed in 1..5) {
            val r = CliTest.run("scenario", "run", "restart-all", "--seed", "$seed")
            r.exitCode shouldBe 0
            r.stdout shouldContain "result: PASSED"
        }
        runDirs() shouldBe before
    }
}
