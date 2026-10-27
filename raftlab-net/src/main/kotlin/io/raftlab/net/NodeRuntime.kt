package io.raftlab.net

import io.raftlab.core.Effect
import io.raftlab.core.Input
import io.raftlab.core.RaftConfig
import io.raftlab.core.RaftMessage
import io.raftlab.core.RaftNode
import io.raftlab.core.SplitMix64
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvServer
import io.raftlab.kv.KvServerOutput
import io.raftlab.kv.KvStateMachine
import io.raftlab.storage.FileStorage
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.TraceSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs one Raft node over TCP.
 *
 * Owns one [FileStorage] on [dataDir], a [KvStateMachine], a [RaftNode] with the members of [cluster], and a
 * [KvServer]. All node and server calls run on one thread named `raftlab-<id>-loop`. Peers and clients share the
 * listening port of this node. A runtime starts once; a restart is a new instance on the same [dataDir] and port.
 * Storage errors are fail-stop: the loop records the failure in [failure] and the node stops itself.
 *
 * @param id this node's id; must be a member of [cluster].
 * @param cluster the cluster configuration, including this node's listening address.
 * @param dataDir the directory that holds this node's durable state.
 * @param tickMillis length of one logical tick in milliseconds; must be positive.
 * @param tracePath file that receives the JSON Lines trace, or null for no trace.
 */
public class NodeRuntime(
    /** This node's id. */
    public val id: NodeId,
    /** The cluster this node belongs to. */
    public val cluster: ClusterConfig,
    /** The directory that holds this node's durable state. */
    public val dataDir: Path,
    /** Length of one logical tick in milliseconds. */
    public val tickMillis: Long = DEFAULT_TICK_MILLIS,
    /** File that receives the JSON Lines trace, or null for no trace. */
    public val tracePath: Path? = null,
) : Closeable {
    init {
        require(id in cluster.members) { "${id.value} is not a member of $cluster" }
        require(tickMillis > 0) { "tickMillis must be positive: $tickMillis" }
    }

    private enum class State { NEW, RUNNING, STOPPED }

    private val lock = Any()

    @Volatile
    private var state = State.NEW

    @Volatile
    private var running: Running? = null

    /** True while the node is running; false before [start], after [stop] and after a failed start. */
    public val isRunning: Boolean
        get() = state == State.RUNNING

    /** The failure that made the loop die, or null. Set before the node stops itself after a fatal error. */
    @Volatile
    public var failure: Throwable? = null
        private set

    /**
     * Opens storage, the trace file, the listening port and the threads, then starts the node.
     *
     * @throws IllegalStateException when the runtime was already started, or when its durable state cannot be
     * opened; resources acquired so far are released before the exception is rethrown.
     */
    public fun start() {
        synchronized(lock) {
            check(state == State.NEW) { "runtime ${id.value} can be started only once" }
            val addr = cluster.address(id)
            val acquired = ArrayList<Closeable>()
            try {
                val storage = FileStorage(dataDir)
                val sink = tracePath?.let { JsonlTraceSink(it, System::currentTimeMillis) }
                sink?.let { acquired += it }
                val trace: TraceSink = sink ?: TraceSink { }
                val stateMachine = KvStateMachine()
                val node = RaftNode(id, cluster.ids, RaftConfig(), storage, stateMachine, SplitMix64(seed()), trace)
                val server = KvServer(node, stateMachine)
                trace.emit { seq, time -> NodeStarted(seq, time, id) }
                val listener = ServerSocket()
                acquired += listener
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(addr.hostString, addr.port), BACKLOG)
                val loopThreads = NetThreads.factory("raftlab-${id.value}-loop", daemon = false)
                val loopExecutor = Executors.newSingleThreadScheduledExecutor(loopThreads)
                acquired += Closeable { shutdownAndAwait(loopExecutor) }
                val ioThreads = NetThreads.factory("raftlab-${id.value}-io", daemon = true)
                val ioExecutor = Executors.newCachedThreadPool(ioThreads)
                acquired += Closeable { shutdownAndAwait(ioExecutor) }
                val transport = TcpTransport(id, cluster.members.filterKeys { it != id })
                acquired += transport
                val r = Running(node, server, stateMachine, listener, transport, loopExecutor, ioExecutor, sink)
                running = r
                r.loopScope.launch { r.runLoop() }
                r.loopScope.launch { r.runTicker() }
                r.ioScope.launch { r.acceptLoop() }
                state = State.RUNNING
                val status = "term=${node.currentTerm} snapshotIndex=${node.snapshotIndex}"
                NetLog.line(id, "started on ${addr.hostString}:${addr.port} $status")
            } catch (e: Throwable) {
                acquired.asReversed().forEach { c -> runCatching { c.close() } }
                state = State.STOPPED
                throw e
            }
        }
    }

    /** Returns the node's current state, read on its loop thread. */
    public fun status(): NodeStatus =
        onLoop { r ->
            NodeStatus(id, r.node.role, r.node.currentTerm, r.node.leaderHint, r.node.commitIndex, r.node.lastApplied)
        }

    /** Returns a copy of the key-value data, read on the loop thread. */
    public fun kvData(): Map<String, String> = onLoop { r -> r.stateMachine.dataSnapshot() }

    /**
     * Stops the node: releases the port, closes connections, stops the threads and waits for them with bounded
     * joins. Idempotent; a node that never started only becomes stopped.
     */
    public fun stop() {
        synchronized(lock) {
            if (state == State.STOPPED) return
            if (state == State.NEW) {
                state = State.STOPPED
                return
            }
            state = State.STOPPED
            val r = running ?: return
            runCatching { r.listener.close() }
            runCatching { r.ioScope.cancel() }
            r.connections.forEach { c -> runCatching { c.close() } }
            runCatching { r.transport.close() }
            runCatching { r.loopScope.cancel() }
            runCatching { r.events.close() }
            runCatching { shutdownAndAwait(r.loopExecutor) }
            runCatching { shutdownAndAwait(r.ioExecutor) }
            runCatching { r.sink?.close() }
            NetLog.line(id, "stopped")
        }
    }

    /** Stops the node; equivalent to [stop]. */
    override fun close() {
        stop()
    }

    private inner class Running(
        val node: RaftNode,
        val server: KvServer,
        val stateMachine: KvStateMachine,
        val listener: ServerSocket,
        val transport: TcpTransport,
        val loopExecutor: ScheduledExecutorService,
        val ioExecutor: ExecutorService,
        val sink: JsonlTraceSink?,
    ) {
        val loopScope = CoroutineScope(SupervisorJob() + loopExecutor.asCoroutineDispatcher())
        val ioScope = CoroutineScope(SupervisorJob() + ioExecutor.asCoroutineDispatcher())
        val events = Channel<LoopEvent>(EVENT_CAPACITY)
        val connections: MutableSet<FrameConnection> = ConcurrentHashMap.newKeySet<FrameConnection>()
        val pendingReplies = HashMap<Long, PendingReply>()
        var nextReplyId = 1L

        suspend fun runLoop() {
            try {
                for (event in events) handle(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fatal(e)
            }
        }

        suspend fun runTicker() {
            try {
                while (true) {
                    delay(tickMillis)
                    onNodeEffects(node.step(Input.Tick))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fatal(e)
            }
        }

        fun acceptLoop() {
            while (true) {
                val socket =
                    try {
                        listener.accept()
                    } catch (e: IOException) {
                        return
                    }
                socket.tcpNoDelay = true
                val conn = FrameConnection(socket)
                connections += conn
                ioScope.launch { serve(conn) }
            }
        }

        private suspend fun serve(conn: FrameConnection) {
            try {
                while (true) {
                    val frame = conn.read() ?: break
                    when (frame) {
                        is Frame.Peer -> events.send(LoopEvent.PeerMessage(frame.message))
                        is Frame.Request -> events.send(LoopEvent.ClientCall(frame.request, conn))
                        is Frame.Response -> throw FrameException("unexpected response frame")
                    }
                }
            } catch (e: FrameException) {
                if (state == State.RUNNING) NetLog.line(id, "connection rejected from ${conn.remote}: ${e.message}")
            } catch (e: IOException) {
            } catch (e: ClosedSendChannelException) {
            } finally {
                connections -= conn
                conn.close()
            }
        }

        private fun handle(event: LoopEvent) {
            when (event) {
                is LoopEvent.PeerMessage -> onPeer(event.message)
                is LoopEvent.ClientCall -> onClient(event)
            }
        }

        private fun onPeer(message: RaftMessage) {
            if (message.to != id) return
            onNodeEffects(node.step(Input.Receive(message)))
        }

        private fun onClient(event: LoopEvent.ClientCall) {
            val rid = nextReplyId++
            pendingReplies[rid] = PendingReply(event.connection, event.request.requestId)
            for (output in server.onClientRequest(event.request.copy(requestId = rid))) {
                when (output) {
                    is KvServerOutput.NodeEffect -> route(output.effect)
                    is KvServerOutput.Reply -> reply(output.response)
                }
            }
        }

        private fun onNodeEffects(effects: List<Effect>) {
            effects.forEach { route(it) }
            for (output in server.onEffects(effects)) {
                if (output is KvServerOutput.Reply) reply(output.response)
            }
        }

        private fun route(effect: Effect) {
            if (effect is Effect.Send) transport.send(effect.msg)
        }

        private fun reply(response: ClientResponse) {
            val pending = pendingReplies.remove(response.requestId) ?: return
            val frame = Frame.Response(ClientResponse(pending.requestId, response.result))
            ioScope.launch {
                try {
                    pending.connection.write(frame)
                } catch (e: IOException) {
                    pending.connection.close()
                }
            }
        }

        private fun fatal(e: Throwable) {
            failure = e
            NetLog.line(id, "fatal: $e")
            Thread({ stop() }, "raftlab-${id.value}-failstop").apply { isDaemon = true }.start()
        }
    }

    private fun <T> onLoop(block: (Running) -> T): T {
        val r = running
        if (r == null || state != State.RUNNING) throw IllegalStateException("runtime ${id.value} is not running")
        return try {
            r.loopExecutor.submit(Callable { block(r) }).get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("runtime ${id.value} is not running", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun shutdownAndAwait(executor: ExecutorService) {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private fun seed(): Long =
        System.nanoTime() xor (id.value.hashCode().toLong() shl 32) xor ProcessHandle.current().pid()

    /** Constants of [NodeRuntime]. */
    public companion object {
        /** Default length of one logical tick in milliseconds. */
        public const val DEFAULT_TICK_MILLIS: Long = 50

        private const val BACKLOG = 64
        private const val EVENT_CAPACITY = 10_000
        private const val QUERY_TIMEOUT_MS = 5_000L
    }
}

private sealed interface LoopEvent {
    class PeerMessage(
        val message: RaftMessage,
    ) : LoopEvent

    class ClientCall(
        val request: ClientRequest,
        val connection: FrameConnection,
    ) : LoopEvent
}

private class PendingReply(
    val connection: FrameConnection,
    val requestId: Long,
)
