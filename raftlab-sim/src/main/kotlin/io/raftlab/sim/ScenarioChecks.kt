package io.raftlab.sim

import io.raftlab.core.EntryPayload
import io.raftlab.core.LogEntry
import io.raftlab.kv.KvCodec
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.kv.KvStateMachine
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import java.util.TreeMap
import java.util.TreeSet

internal object ScenarioChecks {
    fun isWrite(op: KvOp): Boolean = op is KvOp.Put || op is KvOp.Delete || op is KvOp.Cas

    fun liveLeaders(nodes: List<NodeView>): List<NodeView> = nodes.filter { it.up && it.role == Role.LEADER }

    fun oneLeaderPerTerm(trace: List<TraceEvent>): Boolean {
        val terms = trace.filterIsInstance<RoleChanged>().filter { it.to == Role.LEADER }.map { it.term }
        return terms.size == terms.toSortedSet().size
    }

    fun kvAgree(nodes: List<NodeView>): Boolean {
        val live = nodes.filter { it.up }
        val first = live.firstOrNull() ?: return true
        return live.all { it.kvData == first.kvData }
    }

    fun logsConverged(nodes: List<NodeView>): Boolean {
        if (!nodes.all { it.up }) return false
        val head = nodes.firstOrNull() ?: return true
        return nodes.all {
            it.log == head.log && it.commitIndex == head.commitIndex && it.lastApplied == head.lastApplied
        }
    }

    fun commandsOf(
        log: List<LogEntry>,
        clientId: String,
    ): List<KvCommand> {
        val out = ArrayList<KvCommand>()
        for (entry in log) {
            val payload = entry.payload
            if (payload !is EntryPayload.Command) continue
            val cmd =
                try {
                    KvCodec.decodeCommand(payload.bytes)
                } catch (e: IllegalArgumentException) {
                    continue
                }
            if (cmd.clientId == clientId) out += cmd
        }
        return out
    }

    fun acknowledgedWritesSurvive(
        history: History,
        nodes: List<NodeView>,
    ): Boolean {
        val keys = TreeSet<String>()
        history.entries.filter { isWrite(it.op) }.forEach { keys += it.op.key }
        val live = nodes.filter { it.up }
        return keys.all { key ->
            val writes =
                history.entries.filter { isWrite(it.op) && it.op.key == key && it.result !is KvResult.CasFailed }
            val allowed: List<String?> =
                if (writes.isEmpty()) {
                    listOf(null)
                } else {
                    writes.filter { !superseded(it, writes) }.map { effectOf(it.op) }
                }
            live.all { it.kvData[key] in allowed }
        }
    }

    fun appliedAtMostOnce(nodes: List<NodeView>): Boolean =
        nodes.filter { it.up }.all { node ->
            val machine = KvStateMachine()
            val lastSeq = TreeMap<String, Long>()
            val effective = TreeSet<String>()
            for (entry in node.log) {
                val payload = entry.payload
                if (entry.index > node.lastApplied || payload !is EntryPayload.Command) continue
                machine.apply(entry.index, payload.bytes)
                val cmd =
                    try {
                        KvCodec.decodeCommand(payload.bytes)
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                if (cmd.seq <= (lastSeq[cmd.clientId] ?: 0L)) continue
                lastSeq[cmd.clientId] = cmd.seq
                if (!effective.add("${cmd.clientId}#${cmd.seq}")) return false
            }
            machine.dataSnapshot() == node.kvData
        }

    fun sequentialPerClient(history: History): Boolean {
        val clients = TreeSet<String>()
        for (entry in history.entries) clients += entry.clientId
        for (clientId in clients) {
            val model = KvStateMachine()
            for (entry in history.ofClient(clientId).sortedBy { it.opId }) {
                val actual = entry.result ?: return false
                val bytes = model.apply(entry.opId, KvCodec.encodeCommand(KvCommand(clientId, entry.opId, entry.op)))
                if (KvCodec.decodeResult(bytes) != actual) return false
            }
        }
        return true
    }

    private fun effectOf(op: KvOp): String? =
        when (op) {
            is KvOp.Put -> op.value
            is KvOp.Cas -> op.new
            is KvOp.Get, is KvOp.Delete -> null
        }

    private fun superseded(
        write: HistoryEntry,
        writes: List<HistoryEntry>,
    ): Boolean {
        val done = write.returnedAt ?: return false
        return writes.any { other -> other != write && other.returnedAt != null && other.invokedAt > done }
    }
}
