package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import java.util.BitSet

/**
 * Depth-first search for a linearization of the operations of one key.
 *
 * [ops] holds the entries of one key in canonical order. An operation is complete when it returned `Ok` or
 * `CasFailed`; every other entry is pending and may or may not have taken effect. The search extends a partial
 * linearization one operation at a time. An operation can be placed only after every complete operation that
 * precedes it in real time has been placed. A complete operation is placed only when the register model produces
 * its recorded result. A pending operation is placed only when it changes the register. Every complete operation
 * must be placed; pending operations are optional. Visited states are memoized on the set of placed operations and
 * the register value, and the number of states is capped by [budget].
 *
 * Precedence uses the virtual times of the entries. At equal milliseconds, operations of different clients are
 * treated as concurrent, because the history does not record which of a return and an invoke came first. Operations
 * of the same client keep their program order by `opId`.
 */
internal class LinSearch(
    private val key: String,
    private val ops: List<HistoryEntry>,
    private val budget: Long,
) {
    private data class Config(
        val done: BitSet,
        val value: String?,
    )

    private val n = ops.size
    private val complete = BooleanArray(n) { isFinal(ops[it]) }
    private val requiredCount = complete.count { it }
    private val before: Array<BitSet> =
        Array(n) { o ->
            BitSet(n).apply {
                for (p in 0 until n) {
                    if (p != o && complete[p] && precedes(ops[p], ops[o])) set(p)
                }
            }
        }
    private val memo = HashSet<Config>()
    private var explored = 0L
    private var exhausted = false
    private val path = ArrayList<Int>()
    private var best: List<Int> = emptyList()

    private fun isFinal(e: HistoryEntry): Boolean =
        e.returnedAt != null && (e.result is KvResult.Ok || e.result is KvResult.CasFailed)

    private fun precedes(
        a: HistoryEntry,
        b: HistoryEntry,
    ): Boolean {
        val ra = checkNotNull(a.returnedAt)
        return ra < b.invokedAt || (ra == b.invokedAt && a.clientId == b.clientId && a.opId < b.opId)
    }

    private fun expected(
        op: KvOp,
        v: String?,
    ): KvResult =
        when (op) {
            is KvOp.Put -> KvResult.Ok(null)
            is KvOp.Get -> KvResult.Ok(v)
            is KvOp.Delete -> KvResult.Ok(v)
            is KvOp.Cas -> if (v == op.expected) KvResult.Ok(op.new) else KvResult.CasFailed(v)
        }

    private fun next(
        op: KvOp,
        v: String?,
    ): String? =
        when (op) {
            is KvOp.Put -> op.value
            is KvOp.Get -> v
            is KvOp.Delete -> null
            is KvOp.Cas -> if (v == op.expected) op.new else v
        }

    private fun ready(
        o: Int,
        done: BitSet,
    ): Boolean {
        var p = before[o].nextSetBit(0)
        while (p >= 0) {
            if (!done.get(p)) return false
            p = before[o].nextSetBit(p + 1)
        }
        return true
    }

    private fun dfs(
        done: BitSet,
        doneRequired: Int,
        value: String?,
    ): Boolean {
        if (doneRequired == requiredCount) return true
        for (o in 0 until n) {
            if (done.get(o) || !ready(o, done)) continue
            val op = ops[o].op
            if (complete[o]) {
                if (expected(op, value) != ops[o].result) continue
            } else if (next(op, value) == value) {
                continue
            }
            val nd = done.clone() as BitSet
            nd.set(o)
            val nv = next(op, value)
            val c = Config(nd, nv)
            if (c in memo) continue
            if (explored >= budget) {
                exhausted = true
                return false
            }
            memo.add(c)
            explored++
            path.add(o)
            if (path.size > best.size) best = path.toList()
            if (dfs(nd, doneRequired + (if (complete[o]) 1 else 0), nv)) return true
            if (exhausted) return false
            path.removeAt(path.size - 1)
        }
        return false
    }

    fun run(): LinResult {
        memo.add(Config(BitSet(), null))
        explored = 1
        val ok = dfs(BitSet(), 0, null)
        return when {
            ok -> LinResult.Linearizable(explored)
            exhausted -> LinResult.Unknown(key, budget, explored)
            else -> LinResult.NotLinearizable(key, ops, best.map { ops[it] }, failedAt(), explored)
        }
    }

    private fun failedAt(): HistoryEntry? {
        val placed = best.toSet()
        var failed = -1
        for (i in 0 until n) {
            if (!complete[i] || i in placed) continue
            if (failed < 0 || checkNotNull(ops[i].returnedAt) < checkNotNull(ops[failed].returnedAt)) failed = i
        }
        return if (failed < 0) null else ops[failed]
    }
}
