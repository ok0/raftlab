package io.raftlab.net

import io.kotest.matchers.shouldBe
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object NetTest {
    const val TICK_MILLIS: Long = 20

    fun freePorts(n: Int): List<Int> {
        val sockets = ArrayList<ServerSocket>()
        try {
            repeat(n) { sockets += ServerSocket(0, 50, InetAddress.getLoopbackAddress()) }
            return sockets.map { it.localPort }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    fun cluster(ports: List<Int>): ClusterConfig =
        ClusterConfig.parse(ports.mapIndexed { i, p -> "n${i + 1}=127.0.0.1:$p" }.joinToString(","))

    fun awaitUntil(
        timeoutMillis: Long = 10_000,
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("timed out after ${timeoutMillis}ms: $message")
            Thread.sleep(20)
        }
    }

    fun liveThreads(prefix: String): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(prefix) }

    fun request(
        conn: FrameConnection,
        request: ClientRequest,
    ): ClientResponse {
        conn.write(Frame.Request(request))
        while (true) {
            val frame = conn.read() ?: throw AssertionError("connection closed before response ${request.requestId}")
            if (frame !is Frame.Response) throw AssertionError("expected a response frame but got $frame")
            if (frame.response.requestId == request.requestId) return frame.response
        }
    }

    fun connect(address: InetSocketAddress): FrameConnection = FrameConnection.connect(address, 2_000, 5_000)

    fun assertClosedByServer(socket: Socket) {
        socket.soTimeout = 5_000
        try {
            socket.getInputStream().read() shouldBe -1
        } catch (e: SocketTimeoutException) {
            throw AssertionError("server kept the connection open", e)
        } catch (e: SocketException) {
        }
    }
}

internal class TestCluster(
    private val root: Path,
    size: Int = 3,
    private val tickMillis: Long = NetTest.TICK_MILLIS,
) : AutoCloseable {
    val config: ClusterConfig = NetTest.cluster(NetTest.freePorts(size))
    val ids: List<NodeId> = config.ids.toList()
    private val runtimes = LinkedHashMap<NodeId, NodeRuntime>()
    private val clients = ArrayList<TcpKvClient>()

    fun dataDir(id: NodeId): Path = root.resolve(id.value)

    fun start(id: NodeId): NodeRuntime =
        NodeRuntime(id, config, dataDir(id), tickMillis).also {
            runtimes[id] = it
            it.start()
        }

    fun startAll() {
        ids.forEach { start(it) }
    }

    fun runtime(id: NodeId): NodeRuntime = runtimes.getValue(id)

    fun stop(id: NodeId) {
        runtimes.remove(id)?.stop()
    }

    fun runningIds(): List<NodeId> = runtimes.keys.toList()

    fun awaitLeader(timeoutMillis: Long = 10_000): NodeId {
        var found: NodeId? = null
        NetTest.awaitUntil(timeoutMillis, "no leader reported by a majority of ${ids.size} nodes") {
            val statuses = runtimes.values.filter { it.isRunning }.map { it.status() }
            val maxTerm = statuses.maxOfOrNull { it.term }
            val leader = statuses.firstOrNull { it.role == Role.LEADER && it.term == maxTerm }
            val agreeing =
                if (leader == null) {
                    0
                } else {
                    statuses.count { it.term == leader.term && (it.id == leader.id || it.leaderHint == leader.id) }
                }
            if (leader != null && agreeing >= ids.size / 2 + 1) found = leader.id
            found != null
        }
        return checkNotNull(found)
    }

    fun client(
        attemptTimeoutMillis: Long = 500,
        callTimeoutMillis: Long = 10_000,
    ): TcpKvClient =
        TcpKvClient(
            config,
            attemptTimeoutMillis = attemptTimeoutMillis,
            callTimeoutMillis = callTimeoutMillis,
        ).also { clients += it }

    override fun close() {
        clients.forEach { it.close() }
        runtimes.values.forEach { runCatching { it.stop() } }
    }
}
