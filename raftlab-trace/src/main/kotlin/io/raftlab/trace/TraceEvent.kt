package io.raftlab.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Role of a Raft node. */
@Serializable
public enum class Role {
    /** Passive node that follows a leader. */
    FOLLOWER,

    /** Node probing for votes before starting an election. */
    PRE_CANDIDATE,

    /** Node campaigning for leadership. */
    CANDIDATE,

    /** Node that leads the current term. */
    LEADER,
}

/** Kind of a message exchanged between nodes. */
@Serializable
public enum class MessageKind {
    /** Vote request. */
    REQUEST_VOTE,

    /** Reply to a vote request. */
    REQUEST_VOTE_RESPONSE,

    /** Log replication or heartbeat. */
    APPEND_ENTRIES,

    /** Reply to log replication. */
    APPEND_ENTRIES_RESPONSE,

    /** Snapshot transfer. */
    INSTALL_SNAPSHOT,

    /** Reply to snapshot transfer. */
    INSTALL_SNAPSHOT_RESPONSE,

    /** Request for the target to start an election immediately. */
    TIMEOUT_NOW,
}

/** Reason a message was dropped by the network. */
@Serializable
public enum class DropReason {
    /** Dropped by random loss. */
    RANDOM,

    /** Dropped because the endpoints are partitioned. */
    PARTITION,

    /** Dropped because the destination is crashed. */
    CRASHED,
}

/** Kind of a log entry. */
@Serializable
public enum class EntryKind {
    /** No-operation entry. */
    NOOP,

    /** Client command entry. */
    COMMAND,

    /** Membership configuration entry. */
    CONFIG,
}

/** A single event recorded during a simulated run. */
@Serializable
public sealed interface TraceEvent {
    /** Position of this event in the recorded sequence, starting at 0. */
    public val seq: Long

    /** Clock value when the event was recorded. */
    public val time: Long

    /** Node the event concerns, or null for cluster-wide events. */
    public val node: NodeId?
}

/** A node started. */
@Serializable
@SerialName("NodeStarted")
public data class NodeStarted(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
) : TraceEvent

/** A node crashed. */
@Serializable
@SerialName("NodeCrashed")
public data class NodeCrashed(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
) : TraceEvent

/** A node restarted after a crash. */
@Serializable
@SerialName("NodeRestarted")
public data class NodeRestarted(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
) : TraceEvent

/** A node changed role. */
@Serializable
@SerialName("RoleChanged")
public data class RoleChanged(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Role before the change. */
    val from: Role,
    /** Role after the change. */
    val to: Role,
    /** Term in effect when the change happened. */
    val term: Long,
    /** Human-readable cause of the change. */
    val reason: String,
) : TraceEvent

/** A node cast a vote. */
@Serializable
@SerialName("VoteCast")
public data class VoteCast(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Term of the vote. */
    val term: Long,
    /** Candidate the vote was addressed to. */
    val candidate: NodeId,
    /** Whether the vote was granted. */
    val granted: Boolean,
) : TraceEvent

/** A message was sent. */
@Serializable
@SerialName("MessageSent")
public data class MessageSent(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Identifier of the message. */
    val msgId: Long,
    /** Sender of the message. */
    val from: NodeId,
    /** Recipient of the message. */
    val to: NodeId,
    /** Kind of the message. */
    val kind: MessageKind,
    /** Term carried by the message. */
    val term: Long,
    /** Human-readable summary of the message contents. */
    val summary: String,
) : TraceEvent

/** A message was delivered. */
@Serializable
@SerialName("MessageDelivered")
public data class MessageDelivered(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Identifier of the message. */
    val msgId: Long,
) : TraceEvent

/** A message was dropped. */
@Serializable
@SerialName("MessageDropped")
public data class MessageDropped(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Identifier of the message. */
    val msgId: Long,
    /** Why the message was dropped. */
    val reason: DropReason,
) : TraceEvent

/** A message was duplicated by the network. */
@Serializable
@SerialName("MessageDuplicated")
public data class MessageDuplicated(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Identifier of the original message. */
    val msgId: Long,
    /** Identifier of the created copy. */
    val copyId: Long,
) : TraceEvent

/** A log entry was appended. */
@Serializable
@SerialName("LogAppended")
public data class LogAppended(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Index of the entry. */
    val index: Long,
    /** Term of the entry. */
    val term: Long,
    /** Kind of the entry. */
    val entryKind: EntryKind,
    /** Human-readable summary of the entry contents. */
    val summary: String,
) : TraceEvent

/** A log suffix was truncated. */
@Serializable
@SerialName("LogTruncated")
public data class LogTruncated(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** First index removed by the truncation. */
    val fromIndex: Long,
) : TraceEvent

/** The commit index advanced. */
@Serializable
@SerialName("CommitAdvanced")
public data class CommitAdvanced(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** New commit index. */
    val commitIndex: Long,
) : TraceEvent

/** A log entry was applied to the state machine. */
@Serializable
@SerialName("EntryApplied")
public data class EntryApplied(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Index of the applied entry. */
    val index: Long,
) : TraceEvent

/** A snapshot was taken. */
@Serializable
@SerialName("SnapshotTaken")
public data class SnapshotTaken(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Last index covered by the snapshot. */
    val lastIndex: Long,
    /** Term of the last index covered by the snapshot. */
    val lastTerm: Long,
) : TraceEvent

/** A snapshot was installed. */
@Serializable
@SerialName("SnapshotInstalled")
public data class SnapshotInstalled(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Last index covered by the snapshot. */
    val lastIndex: Long,
    /** Term of the last index covered by the snapshot. */
    val lastTerm: Long,
) : TraceEvent

/** The cluster membership configuration changed. */
@Serializable
@SerialName("ConfigChanged")
public data class ConfigChanged(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** New set of members. */
    val members: List<NodeId>,
) : TraceEvent

/** A leadership transfer was started. */
@Serializable
@SerialName("LeadershipTransfer")
public data class LeadershipTransfer(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Node that should become the new leader. */
    val target: NodeId,
) : TraceEvent

/** A client invoked an operation. */
@Serializable
@SerialName("ClientInvoked")
public data class ClientInvoked(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Identifier of the client. */
    val clientId: String,
    /** Identifier of the operation within the client. */
    val opId: Long,
    /** Human-readable description of the operation. */
    val op: String,
) : TraceEvent

/** A client received the result of an operation. */
@Serializable
@SerialName("ClientReturned")
public data class ClientReturned(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Identifier of the client. */
    val clientId: String,
    /** Identifier of the operation within the client. */
    val opId: Long,
    /** Human-readable description of the result. */
    val result: String,
) : TraceEvent

/** The network partition changed. */
@Serializable
@SerialName("PartitionChanged")
public data class PartitionChanged(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Groups of nodes that can communicate with each other. */
    val groups: List<List<NodeId>>,
) : TraceEvent

/** A free-form marker added to the trace. */
@Serializable
@SerialName("Marker")
public data class Marker(
    /** Position of this event in the recorded sequence, starting at 0. */
    override val seq: Long,
    /** Clock value when the event was recorded. */
    override val time: Long,
    /** Node the event concerns, or null for cluster-wide events. */
    override val node: NodeId?,
    /** Text of the marker. */
    val label: String,
) : TraceEvent
