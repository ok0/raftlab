package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult

/** Compact text form of KV operations and results, used in trace events. */
internal object OpText {
    fun op(op: KvOp): String =
        when (op) {
            is KvOp.Put -> "Put(${op.key},${op.value})"
            is KvOp.Get -> "Get(${op.key})"
            is KvOp.Delete -> "Delete(${op.key})"
            is KvOp.Cas -> "Cas(${op.key},${text(op.expected)},${text(op.new)})"
        }

    fun result(result: KvResult): String =
        when (result) {
            is KvResult.Ok -> "Ok(${text(result.value)})"
            is KvResult.CasFailed -> "CasFailed(${text(result.actual)})"
            is KvResult.NotLeader -> "NotLeader(${text(result.hint?.value)})"
            is KvResult.Timeout -> "Timeout"
        }

    private fun text(value: String?): String = value ?: "null"
}
