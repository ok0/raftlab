package io.raftlab.net

import io.raftlab.trace.NodeId

internal object NetLog {
    fun line(
        node: NodeId,
        message: String,
    ) {
        System.err.println("raftlab[${node.value}] $message")
    }
}
