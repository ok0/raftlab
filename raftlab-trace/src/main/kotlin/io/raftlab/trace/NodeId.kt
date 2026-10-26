package io.raftlab.trace

import kotlinx.serialization.Serializable

/** Identifier of a Raft node, ordered lexicographically by [value]. */
@Serializable
@JvmInline
public value class NodeId(
    /** The raw node name, for example `n1`. */
    public val value: String,
) : Comparable<NodeId> {
    /** Compares two ids by their [value]. */
    override fun compareTo(other: NodeId): Int = value.compareTo(other.value)
}
