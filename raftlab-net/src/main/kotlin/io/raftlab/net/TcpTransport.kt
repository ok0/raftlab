package io.raftlab.net

import io.raftlab.core.RaftMessage
import io.raftlab.trace.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Write-only transport that sends Raft messages to peers over TCP.
 *
 * Keeps one outbound connection per peer, sends length-prefixed [Frame.Peer] frames, reconnects with
 * exponential backoff, never blocks [send], and may drop messages.
 */
public class TcpTransport(
    /** The id of the node this transport belongs to. */
    public val self: NodeId,
    peers: Map<NodeId, InetSocketAddress>,
) : Closeable {
    private val executor = Executors.newCachedThreadPool(NetThreads.factory("raftlab-${self.value}-out", daemon = true))
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val outboxes: Map<NodeId, Channel<RaftMessage>>
    private val sockets = ConcurrentHashMap<NodeId, FrameConnection>()

    @Volatile
    private var closed = false

    init {
        require(self !in peers) { "peers must not contain ${self.value}" }
        outboxes =
            peers.mapValues { Channel<RaftMessage>(OUTBOX_CAPACITY, BufferOverflow.DROP_OLDEST) }
        peers.forEach { (peer, address) ->
            scope.launch { pump(peer, address, outboxes.getValue(peer)) }
        }
    }

    /** Queues [message] for its destination peer; unknown peers and a closed transport drop it silently. */
    public fun send(message: RaftMessage) {
        outboxes[message.to]?.trySend(message)
    }

    /** Stops all peer pumps, closes every connection and shuts the executor down. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        outboxes.values.forEach { it.close() }
        sockets.values.forEach { it.close() }
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private suspend fun pump(
        peer: NodeId,
        address: InetSocketAddress,
        outbox: Channel<RaftMessage>,
    ) {
        var backoff = INITIAL_BACKOFF_MS
        while (!closed && currentCoroutineContext().isActive) {
            val conn =
                try {
                    FrameConnection.connect(address, CONNECT_TIMEOUT_MS, 0)
                } catch (e: IOException) {
                    null
                }
            if (conn == null) {
                delay(backoff)
                backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
                continue
            }
            backoff = INITIAL_BACKOFF_MS
            sockets[peer] = conn
            try {
                for (msg in outbox) {
                    try {
                        conn.write(Frame.Peer(msg))
                    } catch (e: FrameException) {
                        NetLog.line(self, "dropped message to ${peer.value}: ${e.message}")
                    }
                }
            } catch (e: IOException) {
            } finally {
                sockets.remove(peer, conn)
                conn.close()
            }
        }
    }

    private companion object {
        const val OUTBOX_CAPACITY = 1024
        const val CONNECT_TIMEOUT_MS = 1000
        const val INITIAL_BACKOFF_MS = 50L
        const val MAX_BACKOFF_MS = 1000L
    }
}
