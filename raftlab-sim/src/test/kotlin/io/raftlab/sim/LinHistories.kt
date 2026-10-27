package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import java.util.TreeMap

object LinHistories {
    fun done(
        client: String,
        opId: Long,
        op: KvOp,
        invokedAt: Long,
        returnedAt: Long,
        result: KvResult,
    ): HistoryEntry = HistoryEntry(client, opId, op, invokedAt, returnedAt, result)

    fun pending(
        client: String,
        opId: Long,
        op: KvOp,
        invokedAt: Long,
    ): HistoryEntry = HistoryEntry(client, opId, op, invokedAt, null, null)

    fun history(vararg entries: HistoryEntry): History = History(entries.toList())

    private class Mix(
        seed: Long,
    ) {
        private var state = seed

        private fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun int(bound: Int): Int = ((next() ushr 33) % bound).toInt()
    }

    private class Draft(
        val client: Int,
        val opId: Long,
        val op: KvOp,
        val invokedAt: Long,
        val linearizedAt: Long,
        val returnedAt: Long,
        val pending: Boolean,
        val applied: Boolean,
    ) {
        var result: KvResult? = null
    }

    private class Gen(
        seed: Long,
    ) {
        val mix = Mix(seed)
        val lastPut = TreeMap<String, String>()
        var counter = 0

        fun put(key: String): KvOp {
            val value = "v${counter++}"
            lastPut[key] = value
            return KvOp.Put(key, value)
        }

        fun cas(key: String): KvOp {
            val expected = if (mix.int(3) == 0) null else lastPut[key]
            val new = if (mix.int(5) == 0) null else "v${counter++}"
            if (new != null) lastPut[key] = new
            return KvOp.Cas(key, expected, new)
        }
    }

    fun generate(
        seed: Long,
        clients: Int = 5,
        keys: Int = 5,
        ops: Int = 200,
        pendingClients: Int = 2,
    ): History {
        val gen = Gen(seed)
        val mix = gen.mix
        val per = ops / clients
        val drafts = ArrayList<Draft>()
        for (c in 0 until clients) {
            var t = c.toLong()
            for (k in 1..per) {
                val inv = t + mix.int(4)
                val lp = inv + 1 + mix.int(8)
                val ret = lp + 1 + mix.int(8)
                t = ret
                val key = "k${mix.int(keys)}"
                val op: KvOp =
                    when (mix.int(10)) {
                        in 0..2 -> gen.put(key)
                        in 3..5 -> KvOp.Get(key)
                        6 -> KvOp.Delete(key)
                        else -> gen.cas(key)
                    }
                val pendingOp = c < pendingClients && k == per
                val applied = !pendingOp || mix.int(2) == 0
                drafts.add(Draft(c, k.toLong(), op, inv, lp, ret, pendingOp, applied))
            }
        }
        val model = TreeMap<String, String>()
        val effective =
            drafts
                .filter { !it.pending || it.applied }
                .sortedWith(compareBy({ it.linearizedAt }, { it.client }, { it.opId }))
        for (d in effective) {
            d.result = resultOf(d.op, model)
        }
        val entries =
            drafts
                .sortedWith(compareBy({ it.invokedAt }, { it.client }, { it.opId }))
                .map { entryOf(it) }
        return History(entries)
    }

    private fun resultOf(
        op: KvOp,
        model: TreeMap<String, String>,
    ): KvResult {
        val cur = model[op.key]
        val result: KvResult =
            when (op) {
                is KvOp.Put -> KvResult.Ok(null)
                is KvOp.Get -> KvResult.Ok(cur)
                is KvOp.Delete -> KvResult.Ok(cur)
                is KvOp.Cas -> if (cur == op.expected) KvResult.Ok(op.new) else KvResult.CasFailed(cur)
            }
        val next: String? =
            when (op) {
                is KvOp.Put -> op.value
                is KvOp.Get -> cur
                is KvOp.Delete -> null
                is KvOp.Cas -> if (cur == op.expected) op.new else cur
            }
        setOrRemove(model, op.key, next)
        return result
    }

    private fun setOrRemove(
        model: TreeMap<String, String>,
        key: String,
        value: String?,
    ) {
        if (value == null) model.remove(key) else model[key] = value
    }

    private fun entryOf(d: Draft): HistoryEntry {
        val client = "c${d.client}"
        return if (d.pending) {
            pending(client, d.opId, d.op, d.invokedAt)
        } else {
            done(client, d.opId, d.op, d.invokedAt, d.returnedAt, checkNotNull(d.result))
        }
    }

    fun corruptFirstRead(history: History): History {
        val i = history.entries.indexOfFirst { !it.pending && it.op is KvOp.Get && it.result is KvResult.Ok }
        require(i >= 0) { "history has no completed Get with an Ok result" }
        val corrupted = KvResult.Ok("corrupted")
        val entries = history.entries.mapIndexed { j, e -> if (j == i) e.copy(result = corrupted) else e }
        return History(entries)
    }
}
