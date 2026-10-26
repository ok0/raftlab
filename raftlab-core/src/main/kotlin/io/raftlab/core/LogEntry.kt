package io.raftlab.core

import io.raftlab.trace.NodeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Term number of a Raft entry; terms start at 0 and never decrease. */
public typealias Term = Long

/** One-based position of an entry in the Raft log; 0 denotes the empty prefix. */
public typealias Index = Long

/** A replicated log entry with its position [index], creating [term] and [payload]. */
@Serializable
public data class LogEntry(
    /** Position of this entry in the log. */
    public val index: Long,
    /** Term in which the entry was created. */
    public val term: Long,
    /** Content carried by this entry. */
    public val payload: EntryPayload,
)

/** Content carried by a [LogEntry]. */
@Serializable
public sealed interface EntryPayload {
    /** Leader no-op entry, appended at the start of a term. */
    @Serializable
    @SerialName("NoOp")
    public data object NoOp : EntryPayload

    /** Opaque state machine command. */
    @Serializable
    @SerialName("Command")
    public class Command(
        /** Raw command bytes passed to the state machine. */
        public val bytes: ByteArray,
    ) : EntryPayload {
        /** Compares commands by their byte content. */
        override fun equals(other: Any?): Boolean = other is Command && bytes.contentEquals(other.bytes)

        /** Hashes commands by their byte content. */
        override fun hashCode(): Int = bytes.contentHashCode()

        /** Renders the command with its byte content. */
        override fun toString(): String = "Command(${bytes.contentToString()})"
    }

    /** Cluster membership configuration in force from this entry onwards. */
    @Serializable
    @SerialName("Config")
    public data class Config(
        /** Members of the cluster under this configuration. */
        public val members: Set<NodeId>,
    ) : EntryPayload
}
