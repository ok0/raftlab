package io.raftlab.net

import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvClientAction
import io.raftlab.kv.KvClientCore
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A key-value client that talks to a [cluster] over TCP.
 *
 * One operation runs at a time; calls are serialized by a mutex. Retries reuse the same `seq`, so the server
 * deduplicates them. A call fails with [KvClientTimeoutException] within about
 * `callTimeoutMillis + 2 * attemptTimeoutMillis`. After a timed-out call the client continues under a fresh session
 * id `"$clientId#<n>"`, because the abandoned operation may still be applied. After [close] every call throws
 * [IllegalStateException] immediately. The client uses [Dispatchers.IO] and owns no threads.
 */
public class TcpKvClient(
    /** The cluster to connect to. */
    public val cluster: ClusterConfig,
    /** Identifier of this client's session. */
    public val clientId: String = "client-${UUID.randomUUID()}",
    /** Timeout for a single attempt against one node, in milliseconds. */
    public val attemptTimeoutMillis: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
    /** Overall timeout for one call, in milliseconds. */
    public val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MS,
) : Closeable {
    init {
        require(clientId.isNotBlank())
        require(attemptTimeoutMillis in 1..Int.MAX_VALUE.toLong())
        require(callTimeoutMillis > 0)
    }

    private val mutex = Mutex()
    private var generation = 0
    private var core = KvClientCore(clientId, cluster.ids)
    private val connections = ConcurrentHashMap<NodeId, FrameConnection>()

    @Volatile
    private var closed = false

    internal val leaderGuess: NodeId
        get() = core.leaderGuess

    internal val openConnections: Int
        get() = connections.size

    /**
     * Stores [value] under [key] and returns the result.
     *
     * @return [KvResult.Ok] on success.
     * @throws KvClientTimeoutException when no leader answered within the call timeout.
     * @throws IllegalStateException when the client is closed.
     */
    public suspend fun put(
        key: String,
        value: String,
    ): KvResult = call(KvOp.Put(key, value))

    /**
     * Reads the value under [key] and returns the result.
     *
     * @return [KvResult.Ok] carrying the value, or with a null value when the key is absent.
     * @throws KvClientTimeoutException when no leader answered within the call timeout.
     * @throws IllegalStateException when the client is closed.
     */
    public suspend fun get(key: String): KvResult = call(KvOp.Get(key))

    /**
     * Removes [key] and returns the result.
     *
     * @return [KvResult.Ok] carrying the previous value.
     * @throws KvClientTimeoutException when no leader answered within the call timeout.
     * @throws IllegalStateException when the client is closed.
     */
    public suspend fun delete(key: String): KvResult = call(KvOp.Delete(key))

    /**
     * Sets [key] to [new] only when its current value equals [expected] and returns the result.
     *
     * @return [KvResult.Ok] when the swap happened, or [KvResult.CasFailed] when it did not.
     * @throws KvClientTimeoutException when no leader answered within the call timeout.
     * @throws IllegalStateException when the client is closed.
     */
    public suspend fun cas(
        key: String,
        expected: String?,
        new: String?,
    ): KvResult = call(KvOp.Cas(key, expected, new))

    /** Closes every open connection; subsequent calls throw [IllegalStateException]. */
    override fun close() {
        closed = true
        connections.values.forEach { it.close() }
        connections.clear()
    }

    private suspend fun call(op: KvOp): KvResult {
        check(!closed) { "client is closed" }
        return mutex.withLock {
            check(!closed) { "client is closed" }
            withTimeoutOrNull(callTimeoutMillis) { execute(op) } ?: abandon()
        }
    }

    private fun abandon(): Nothing {
        connections.values.forEach { it.close() }
        connections.clear()
        generation++
        core = KvClientCore("$clientId#$generation", cluster.ids)
        throw KvClientTimeoutException("no leader answered within $callTimeoutMillis ms")
    }

    private suspend fun execute(op: KvOp): KvResult {
        var action: KvClientAction = core.submit(op)
        var retries = 0
        while (true) {
            check(!closed) { "client is closed" }
            when (val a = action) {
                is KvClientAction.Done -> {
                    return a.result
                }

                is KvClientAction.Send -> {
                    if (retries > 0) delay(minOf(RETRY_STEP_MS * retries, MAX_RETRY_DELAY_MS))
                    retries++
                    val response = attempt(a)
                    action = if (response == null) core.onTimeout() else core.onResponse(response)
                }

                KvClientAction.Ignore -> {
                    action = core.onTimeout()
                }
            }
        }
    }

    private suspend fun attempt(send: KvClientAction.Send): ClientResponse? {
        return withContext(Dispatchers.IO) {
            val address = cluster.members[send.target] ?: return@withContext null
            try {
                val conn = connections[send.target] ?: openConnection(send.target, address)
                if (closed) {
                    conn.close()
                    null
                } else {
                    exchange(conn, send.request)
                }
            } catch (e: IOException) {
                connections.remove(send.target)?.close()
                null
            }
        }
    }

    private fun openConnection(
        target: NodeId,
        address: InetSocketAddress,
    ): FrameConnection {
        val timeout = attemptTimeoutMillis.toInt()
        return FrameConnection.connect(address, timeout, timeout).also { connections[target] = it }
    }

    private fun exchange(
        conn: FrameConnection,
        request: ClientRequest,
    ): ClientResponse {
        conn.write(Frame.Request(request))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMillis)
        while (System.nanoTime() < deadline) {
            val frame = conn.read() ?: throw EOFException("connection closed by server")
            if (frame is Frame.Response && frame.response.requestId == request.requestId) return frame.response
        }
        throw SocketTimeoutException("no matching response within $attemptTimeoutMillis ms")
    }

    /** Default timeouts for [TcpKvClient]. */
    public companion object {
        /** Default timeout for a single attempt against one node, in milliseconds. */
        public const val DEFAULT_ATTEMPT_TIMEOUT_MS: Long = 1000

        /** Default overall timeout for one call, in milliseconds. */
        public const val DEFAULT_CALL_TIMEOUT_MS: Long = 10_000

        private const val RETRY_STEP_MS = 25L
        private const val MAX_RETRY_DELAY_MS = 500L
    }
}
