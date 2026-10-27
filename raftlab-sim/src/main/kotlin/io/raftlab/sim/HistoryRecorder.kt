package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.ClientInvoked
import io.raftlab.trace.ClientReturned
import java.util.TreeMap

internal class HistoryRecorder(
    private val sim: Simulation,
) {
    private val entries = ArrayList<HistoryEntry>()
    private val index = TreeMap<String, Int>()

    fun invoked(
        clientId: String,
        opId: Long,
        op: KvOp,
    ) {
        val key = "$clientId#$opId"
        require(key !in index) { "operation $key already invoked" }
        index[key] = entries.size
        entries += HistoryEntry(clientId, opId, op, sim.now, null, null)
        sim.emit { seq, time -> ClientInvoked(seq, time, null, clientId, opId, OpText.op(op)) }
    }

    fun returned(
        clientId: String,
        opId: Long,
        result: KvResult,
    ) {
        val key = "$clientId#$opId"
        val i = checkNotNull(index[key]) { "operation $key was never invoked" }
        check(entries[i].pending) { "operation $key already returned" }
        entries[i] = entries[i].copy(returnedAt = sim.now, result = result)
        sim.emit { seq, time -> ClientReturned(seq, time, null, clientId, opId, OpText.result(result)) }
    }

    fun history(): History = History(entries.toList())
}
