package io.raftlab.core

import io.raftlab.trace.NodeId

/** An input fed to [RaftNode.step] by a driver. */
public sealed interface Input {
    /** One unit of logical time. */
    public data object Tick : Input

    /** Delivery of [msg] to the node. */
    public data class Receive(
        /** The delivered message. */
        public val msg: RaftMessage,
    ) : Input

    /** Client request to append [command] as a new log entry; only a leader accepts it. */
    public class Propose(
        /** Caller-chosen id echoed in the eventual [Effect.Applied] or [Effect.Rejected]. */
        public val requestId: Long,
        /** Raw command bytes to append. */
        public val command: ByteArray,
    ) : Input {
        /** Compares proposals by [requestId] and by [command] content. */
        override fun equals(other: Any?): Boolean =
            other is Propose && requestId == other.requestId && command.contentEquals(other.command)

        /** Hashes proposals by [requestId] and by [command] content. */
        override fun hashCode(): Int = 31 * requestId.hashCode() + command.contentHashCode()

        /** Renders the request id and the [command] content. */
        override fun toString(): String = "Propose(requestId=$requestId, command=${command.contentToString()})"
    }

    /**
     * Linearizable read request. Never appends a log entry. A leader that has committed an entry of its current term
     * records `readIndex = commitIndex`, confirms leadership with a majority of the current configuration acknowledging
     * a heartbeat round sent after this request, and then emits [Effect.ReadReady]; the caller reads its state machine,
     * whose applied index is then at least `readIndex`. A non-leader, or a leader that loses leadership while the read
     * is pending, answers `Effect.Rejected(requestId, Rejection.NotLeader(hint))`; a leader without a current-term commit
     * answers `Rejection.Busy`. A leader that never regains a quorum never answers.
     */
    public data class ReadIndex(
        /** Caller-chosen id echoed in the eventual [Effect.ReadReady] or [Effect.Rejected]. */
        public val requestId: Long,
    ) : Input

    /**
     * Single-server membership change; exactly one of [add] or [remove] must be set. Success is an
     * `Effect.Applied(requestId, index, empty)` when the `Config` entry commits on this leader; otherwise
     * `Effect.Rejected`.
     */
    public data class ChangeMembership(
        /** Caller-chosen id echoed in the eventual [Effect.Applied] or [Effect.Rejected]. */
        public val requestId: Long,
        /** Server to add: it first catches up as a non-voting member, then the configuration gains it. */
        public val add: NodeId?,
        /** Server to remove from the configuration. */
        public val remove: NodeId?,
    ) : Input
}
