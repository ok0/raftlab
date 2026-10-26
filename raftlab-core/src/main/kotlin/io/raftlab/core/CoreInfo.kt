package io.raftlab.core

import io.raftlab.trace.NodeId

/** Placeholder entry point of the consensus module until the Raft core lands. */
public object CoreInfo {
    /** Gradle module name of this library. */
    public const val MODULE: String = "raftlab-core"

    /** Returns a human-readable label for [id], for example `node:n1`. */
    public fun nodeLabel(id: NodeId): String = "node:${id.value}"
}
