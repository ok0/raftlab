package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

internal class NodeKvCommandTest {
    private fun nodeArgs(
        i: Int,
        cluster: String,
        tmp: Path,
    ): List<String> =
        listOf(
            "node",
            "--id",
            "n$i",
            "--cluster",
            cluster,
            "--data-dir",
            tmp.resolve("n$i").toString(),
            "--tick-millis",
            "20",
        )

    private fun expectLine(r: CliRun) {
        val lines = r.stderr.lines().filter { it.isNotBlank() }
        lines.size shouldBe 1
        lines.single() shouldStartWith "error:"
    }

    private fun bindsLoopback(port: Int) {
        ServerSocket(port, 50, InetAddress.getLoopbackAddress()).close()
    }

    @Test
    fun clusterServesKvCommands(
        @TempDir tmp: Path,
    ) {
        val ports = CliTest.freePorts(3)
        val c = CliTest.cluster(ports)
        val handles = (1..3).map { NodeHandle(nodeArgs(it, c, tmp)) }
        try {
            handles.forEach { it.awaitStarted() }
            CliTest.awaitUntil(20_000, "no leader accepted put") {
                CliTest.run("kv", "--cluster", c, "--timeout-millis", "2000", "put", "a", "1").exitCode == 0
            }

            fun kv(vararg args: String): CliRun = CliTest.run("kv", "--cluster", c, "--timeout-millis", "5000", *args)

            kv("get", "a").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "1\n"
            }
            kv("get", "missing").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "(nil)\n"
            }
            kv("delete", "a").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "OK\n"
            }
            kv("get", "a").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "(nil)\n"
            }
            kv("cas", "--expect-absent", "b", "2").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "OK\n"
            }
            kv("cas", "b", "2", "3").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "OK\n"
            }
            kv("cas", "b", "9", "4").let {
                it.exitCode shouldBe 1
                it.stdout shouldBe "CAS_FAILED 3\n"
                expectLine(it)
            }
            kv("cas", "--new-absent", "b", "3").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "OK\n"
            }
            kv("get", "b").let {
                it.exitCode shouldBe 0
                it.stdout shouldBe "(nil)\n"
            }
            kv("cas", "c", "1", "2").let {
                it.exitCode shouldBe 1
                it.stdout shouldBe "CAS_FAILED (nil)\n"
            }
            kv("cas", "b", "1").exitCode shouldBe 2

            val stopCodes = handles.map { it.stop() }
            stopCodes shouldBe listOf(0, 0, 0)
            ports.forEach { bindsLoopback(it) }

            val again = NodeHandle(nodeArgs(1, c, tmp))
            try {
                again.awaitStarted()
                again.stop() shouldBe 0
            } finally {
                again.stop()
            }
        } finally {
            handles.forEach { it.stop() }
        }
    }

    @Test
    fun noLeaderFailsInBoundedTime() {
        val c = CliTest.cluster(CliTest.freePorts(3))
        val start = System.nanoTime()
        val r = CliTest.run("kv", "--cluster", c, "--timeout-millis", "3000", "get", "k")
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        r.exitCode shouldBe 1
        expectLine(r)
        (elapsedMillis < 30_000) shouldBe true
    }

    @Test
    fun idNotInClusterIsUsageErrorWithoutBinding(
        @TempDir tmp: Path,
    ) {
        val p = CliTest.freePorts(1).single()
        val data = tmp.resolve("d")
        val r = CliTest.run("node", "--id", "n9", "--cluster", "n1=127.0.0.1:$p", "--data-dir", data.toString())
        r.exitCode shouldBe 2
        bindsLoopback(p)
        Files.exists(data) shouldBe false
    }

    @Test
    fun bindFailureExitsNonzero(
        @TempDir tmp: Path,
    ) {
        val p = CliTest.freePorts(1).single()
        ServerSocket(p, 50, InetAddress.getLoopbackAddress()).use {
            val h =
                NodeHandle(
                    listOf(
                        "node",
                        "--id",
                        "n1",
                        "--cluster",
                        "n1=127.0.0.1:$p",
                        "--data-dir",
                        tmp.resolve("d").toString(),
                    ),
                )
            try {
                CliTest.awaitUntil(10_000, "node did not report a failed start") { "failed to start" in h.stderr() }
            } finally {
                h.stop() shouldBe 1
            }
        }
    }
}
