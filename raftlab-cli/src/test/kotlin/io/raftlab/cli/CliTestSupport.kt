package io.raftlab.cli

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class CliRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal object CliTest {
    fun run(vararg args: String): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env =
            CliEnvironment(
                PrintStream(out, true, Charsets.UTF_8),
                PrintStream(err, true, Charsets.UTF_8),
            ) { error("node commands need a stop signal") }
        val code = RaftlabCli.run(args.toList(), env)
        return CliRun(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    fun freePorts(n: Int): List<Int> {
        val sockets = ArrayList<ServerSocket>()
        try {
            repeat(n) { sockets += ServerSocket(0, 50, InetAddress.getLoopbackAddress()) }
            return sockets.map { it.localPort }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    fun cluster(ports: List<Int>): String = ports.mapIndexed { i, p -> "n${i + 1}=127.0.0.1:$p" }.joinToString(",")

    fun awaitUntil(
        timeoutMillis: Long,
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("timed out after ${timeoutMillis}ms: $message")
            Thread.sleep(50)
        }
    }

    fun prop(name: String): Path = Path.of(System.getProperty(name) ?: error("missing system property $name"))
}

internal class NodeHandle(
    private val args: List<String>,
) {
    private val latch = CountDownLatch(1)
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val code = AtomicInteger(-1)
    private val thread =
        Thread {
            val env =
                CliEnvironment(
                    PrintStream(out, true, Charsets.UTF_8),
                    PrintStream(err, true, Charsets.UTF_8),
                ) { LatchStopSignal(latch) }
            code.set(RaftlabCli.run(args, env))
        }.apply { isDaemon = true }

    init {
        thread.start()
    }

    fun stderr(): String = err.toString(Charsets.UTF_8)

    fun awaitStarted() {
        CliTest.awaitUntil(15_000, "node did not start") { "started" in stderr() || code.get() >= 0 }
        code.get() shouldBe -1
    }

    fun stop(): Int {
        latch.countDown()
        thread.join(15_000)
        return code.get()
    }
}
