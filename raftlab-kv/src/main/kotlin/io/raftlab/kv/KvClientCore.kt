package io.raftlab.kv

import io.raftlab.trace.NodeId

/**
 * A pure, deterministic client state machine. It has no clock, threads or I/O; timeouts are measured by the driver
 * and signalled via [onTimeout]. At most one operation is in flight at a time.
 */
public class KvClientCore(
    /** Identifier of this client; must not be blank. */
    public val clientId: String,
    members: Set<NodeId>,
) {
    private val order: List<NodeId> = members.sorted()

    /** Target of the next [submit]; updated only by a successful response. */
    public var leaderGuess: NodeId = order.first()
        private set

    /** Sequence number of the current or next operation. */
    public var nextSeq: Long = 1
        private set

    /** The request to (re)send now; null when idle. */
    public var inFlight: KvClientAction.Send? = null
        private set

    private var nextRequestId: Long = 1

    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(order.isNotEmpty()) { "members must not be empty" }
    }

    /** Starts [op] at [leaderGuess] and returns the request to send. */
    public fun submit(op: KvOp): KvClientAction.Send {
        check(inFlight == null) { "an operation is already in flight" }
        return sendTo(leaderGuess, KvCommand(clientId, nextSeq, op))
    }

    /** Handles a response from a server and returns the next action. */
    public fun onResponse(response: ClientResponse): KvClientAction {
        val current = inFlight ?: return KvClientAction.Ignore
        if (response.requestId != current.request.requestId) return KvClientAction.Ignore
        return when (val r = response.result) {
            is KvResult.Ok, is KvResult.CasFailed -> {
                leaderGuess = current.target
                nextSeq++
                inFlight = null
                KvClientAction.Done(r)
            }

            is KvResult.NotLeader -> {
                val hint = r.hint
                sendTo(if (hint != null && hint in order) hint else next(current.target), current.request.command)
            }

            KvResult.Timeout -> {
                sendTo(next(current.target), current.request.command)
            }
        }
    }

    /** Signals that the driver's timer for the in-flight attempt expired and returns the retry action. */
    public fun onTimeout(): KvClientAction {
        val current = inFlight ?: return KvClientAction.Ignore
        return sendTo(next(current.target), current.request.command)
    }

    private fun next(target: NodeId): NodeId = order[(order.indexOf(target) + 1) % order.size]

    private fun sendTo(
        target: NodeId,
        command: KvCommand,
    ): KvClientAction.Send {
        val send = KvClientAction.Send(target, ClientRequest(nextRequestId++, command))
        inFlight = send
        return send
    }
}
