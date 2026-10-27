package io.raftlab.sim

import io.raftlab.core.RandomSource
import io.raftlab.kv.KvOp
import java.util.TreeMap

/**
 * Draws the operation list of one simulated client.
 *
 * For each operation exactly two draws are made in this order: `r = nextLong(100)` selects the kind (below 40 Put,
 * below 70 Get, below 85 Delete, otherwise Cas) and `k = nextLong(keys.size)` selects the key. With `perClientKeys`
 * the key is prefixed by `"$clientId."`. Values are `"$clientId:$j"` for the j-th operation. Cas uses the value this
 * client last planned for the key (null when absent) as `expected`.
 */
internal object ClientOps {
    fun draw(
        clientId: String,
        load: ClientLoad,
        random: RandomSource,
    ): List<KvOp> {
        val planned = TreeMap<String, String?>()
        val ops = ArrayList<KvOp>()
        for (j in 1..load.opsPerClient) {
            val r = random.nextLong(100)
            val k = random.nextLong(load.keys.size.toLong())
            val base = load.keys[k.toInt()]
            val key = if (load.perClientKeys) "$clientId.$base" else base
            val value = "$clientId:$j"
            val op =
                when {
                    r < 40 -> KvOp.Put(key, value)
                    r < 70 -> KvOp.Get(key)
                    r < 85 -> KvOp.Delete(key)
                    else -> KvOp.Cas(key, planned[key], value)
                }
            when (op) {
                is KvOp.Put -> planned[key] = value
                is KvOp.Delete -> planned[key] = null
                is KvOp.Cas -> planned[key] = value
                is KvOp.Get -> Unit
            }
            ops += op
        }
        return ops
    }
}
