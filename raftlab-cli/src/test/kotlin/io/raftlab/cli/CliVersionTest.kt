package io.raftlab.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class CliVersionTest {
    @Test
    fun versionOptionPrintsBuildVersion() {
        val expected = requireNotNull(System.getProperty("raftlab.expectedVersion"))
        val r = CliTest.run("--version")
        r.exitCode shouldBe 0
        r.stdout shouldBe "raftlab version $expected\n"
        r.stderr shouldBe ""
    }

    @Test
    fun resourceVersionMatchesBuild() {
        RaftlabVersion.current() shouldBe System.getProperty("raftlab.expectedVersion")
    }
}
