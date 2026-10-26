package io.raftlab.core

import io.raftlab.trace.NodeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A message exchanged between Raft nodes. */
@Serializable
public sealed interface RaftMessage {
    /** Term of the sender when the message was created. */
    public val term: Long

    /** Node that sent the message. */
    public val from: NodeId

    /** Node the message is addressed to. */
    public val to: NodeId
}

/** Asks a peer for its vote, optionally as a pre-vote that does not increment terms. */
@Serializable
@SerialName("RequestVote")
public data class RequestVote(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
    /** Index of the candidate's last log entry. */
    public val lastLogIndex: Long,
    /** Term of the candidate's last log entry. */
    public val lastLogTerm: Long,
    /** Whether this is a pre-vote request. */
    public val preVote: Boolean,
) : RaftMessage

/** Reply to a [RequestVote]. */
@Serializable
@SerialName("RequestVoteResponse")
public data class RequestVoteResponse(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
    /** Whether the vote was granted. */
    public val granted: Boolean,
    /** Whether the original request was a pre-vote request. */
    public val preVote: Boolean,
) : RaftMessage

/** Replicates log entries from a leader, or serves as a heartbeat when [entries] is empty. */
@Serializable
@SerialName("AppendEntries")
public data class AppendEntries(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
    /** Index of the entry immediately preceding [entries]. */
    public val prevLogIndex: Long,
    /** Term of the entry at [prevLogIndex]. */
    public val prevLogTerm: Long,
    /** Entries to replicate, in index order. */
    public val entries: List<LogEntry>,
    /** Leader's commit index. */
    public val leaderCommit: Long,
    /** Sequence number used to confirm leadership for linearizable reads. */
    public val readSeq: Long,
) : RaftMessage

/** Reply to an [AppendEntries]; conflict fields are 0 when not applicable. */
@Serializable
@SerialName("AppendEntriesResponse")
public data class AppendEntriesResponse(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
    /** Whether the follower matched the entry at the previous index. */
    public val success: Boolean,
    /** Highest index known to be replicated on the follower when [success] holds. */
    public val matchIndex: Long,
    /** First index of the conflicting term on the follower, or 0 when not applicable. */
    public val conflictIndex: Long,
    /** Term of the conflicting entry on the follower, or 0 when not applicable. */
    public val conflictTerm: Long,
    /** Echo of the request's read sequence number. */
    public val readSeq: Long,
) : RaftMessage

/** Sends a snapshot to a lagging follower. Equality and string form cover the snapshot bytes by content. */
@Serializable
@SerialName("InstallSnapshot")
public class InstallSnapshot(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
    /** Index of the last entry covered by the snapshot. */
    public val lastIndex: Long,
    /** Term of the last entry covered by the snapshot. */
    public val lastTerm: Long,
    /** Cluster membership at the snapshot point. */
    public val members: Set<NodeId>,
    /** Opaque snapshot bytes. */
    public val data: ByteArray,
) : RaftMessage {
    /** Compares snapshot messages by all fields, with [data] compared by content. */
    override fun equals(other: Any?): Boolean =
        other is InstallSnapshot &&
            term == other.term &&
            from == other.from &&
            to == other.to &&
            lastIndex == other.lastIndex &&
            lastTerm == other.lastTerm &&
            members == other.members &&
            data.contentEquals(other.data)

    /** Hashes snapshot messages by all fields, with [data] hashed by content. */
    override fun hashCode(): Int {
        var result = term.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + lastIndex.hashCode()
        result = 31 * result + lastTerm.hashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    /** Renders all fields, with [data] rendered by content. */
    override fun toString(): String =
        "InstallSnapshot(term=$term, from=$from, to=$to, lastIndex=$lastIndex, " +
            "lastTerm=$lastTerm, members=$members, data=${data.contentToString()})"
}

/** Reply to an [InstallSnapshot]. */
@Serializable
@SerialName("InstallSnapshotResponse")
public data class InstallSnapshotResponse(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
    /** Index of the snapshot the follower now holds. */
    public val lastIndex: Long,
) : RaftMessage

/** Tells a follower to start an election immediately, used for leadership transfer. */
@Serializable
@SerialName("TimeoutNow")
public data class TimeoutNow(
    /** Term of the sender when the message was created. */
    override val term: Long,
    /** Node that sent the message. */
    override val from: NodeId,
    /** Node the message is addressed to. */
    override val to: NodeId,
) : RaftMessage
