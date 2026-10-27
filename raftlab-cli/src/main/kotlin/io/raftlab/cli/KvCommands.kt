package io.raftlab.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.raftlab.kv.KvResult
import io.raftlab.net.ClusterConfig
import io.raftlab.net.KvClientTimeoutException
import io.raftlab.net.TcpKvClient
import kotlinx.coroutines.runBlocking
import java.io.IOException

internal class KvCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "kv") {
    val cluster: ClusterConfig by option(
        "--cluster",
        help = "id=host:port,... of all members",
    ).convert { text ->
        try {
            ClusterConfig.parse(text)
        } catch (e: IllegalArgumentException) {
            fail("invalid --cluster: ${e.message}")
        }
    }.required()

    val timeoutMillis: Long by option(
        "--timeout-millis",
        help = "Upper bound for the operation in ms (default 10000)",
    ).long().restrictTo(min = 1L).default(CliDefaults.KV_TIMEOUT_MILLIS)

    init {
        subcommands(
            KvPutCommand(this, env),
            KvGetCommand(this, env),
            KvDeleteCommand(this, env),
            KvCasCommand(this, env),
        )
    }

    override fun help(context: Context): String = "Run one key-value operation against a raftlab cluster over TCP."

    override fun run() = Unit

    fun execute(
        op: String,
        call: suspend (TcpKvClient) -> KvResult,
    ): KvResult =
        TcpKvClient(
            cluster,
            attemptTimeoutMillis = minOf(TcpKvClient.DEFAULT_ATTEMPT_TIMEOUT_MS, timeoutMillis),
            callTimeoutMillis = timeoutMillis,
        ).use { client ->
            try {
                runBlocking { call(client) }
            } catch (e: KvClientTimeoutException) {
                env.err.println("error: kv $op timed out after $timeoutMillis ms: no reachable leader (${e.message})")
                checkFailed()
            } catch (e: IOException) {
                env.err.println("error: kv $op failed: ${e.message}")
                checkFailed()
            }
        }

    fun failWith(
        op: String,
        result: KvResult,
    ): Nothing {
        val reason =
            when (result) {
                is KvResult.NotLeader -> {
                    "no reachable leader (hint ${result.hint?.value ?: "none"})"
                }

                KvResult.Timeout -> {
                    "timed out"
                }

                else -> {
                    "unexpected result $result"
                }
            }
        env.err.println("error: kv $op failed: $reason")
        checkFailed()
    }
}

internal class KvPutCommand(
    private val kv: KvCommand,
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "put") {
    private val key by argument("KEY")
    private val value by argument("VALUE")

    override fun run() {
        when (val result = kv.execute("put") { it.put(key, value) }) {
            is KvResult.Ok -> env.out.println("OK")
            else -> kv.failWith("put", result)
        }
    }
}

internal class KvGetCommand(
    private val kv: KvCommand,
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "get") {
    private val key by argument("KEY")

    override fun run() {
        when (val result = kv.execute("get") { it.get(key) }) {
            is KvResult.Ok -> env.out.println(result.value ?: "(nil)")
            else -> kv.failWith("get", result)
        }
    }
}

internal class KvDeleteCommand(
    private val kv: KvCommand,
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "delete") {
    private val key by argument("KEY")

    override fun run() {
        when (val result = kv.execute("delete") { it.delete(key) }) {
            is KvResult.Ok -> env.out.println("OK")
            else -> kv.failWith("delete", result)
        }
    }
}

internal class KvCasCommand(
    private val kv: KvCommand,
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "cas") {
    private val key by argument("KEY")
    private val first by argument("EXPECTED").optional()
    private val second by argument("NEW").optional()
    private val expectAbsent by option("--expect-absent").flag()
    private val newAbsent by option("--new-absent").flag()

    override fun help(context: Context): String =
        "Compare-and-set KEY from EXPECTED to NEW. Use --expect-absent instead of EXPECTED to require that KEY is " +
            "absent, and --new-absent instead of NEW to delete KEY on success. Prints OK, or CAS_FAILED <actual> " +
            "((nil) when absent) and exits 1."

    override fun run() {
        val values = listOfNotNull(first, second)
        val needed = 2 - (if (expectAbsent) 1 else 0) - (if (newAbsent) 1 else 0)
        if (values.size != needed) usageError("cas needs $needed value argument(s) with the given flags")
        val expected = if (expectAbsent) null else values[0]
        val new = if (newAbsent) null else values.last()
        when (val result = kv.execute("cas") { it.cas(key, expected, new) }) {
            is KvResult.Ok -> {
                env.out.println("OK")
            }

            is KvResult.CasFailed -> {
                env.out.println("CAS_FAILED ${result.actual ?: "(nil)"}")
                env.err.println("error: kv cas failed: current value is ${result.actual ?: "(nil)"}")
                checkFailed()
            }

            else -> {
                kv.failWith("cas", result)
            }
        }
    }
}
