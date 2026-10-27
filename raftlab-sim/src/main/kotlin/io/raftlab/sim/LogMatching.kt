package io.raftlab.sim

import java.util.TreeSet

/**
 * Checks the Log Matching property: when two live nodes hold an entry with the same index and term,
 * their logs are identical up to that index.
 *
 * Each divergence is reported once per pair of nodes and index.
 */
public class LogMatching : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "log-matching"

    private val reported = TreeSet<String>()

    /**
     * Checks the given node snapshots for diverging logs between live nodes.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of newly found divergences.
     */
    public override fun check(nodes: List<NodeState>): List<String> {
        val out = mutableListOf<String>()
        val live = nodes.filter { it.up }
        for (i in live.indices) {
            for (j in i + 1 until live.size) {
                val a = live[i]
                val b = live[j]
                val n = minOf(a.log.size, b.log.size)
                val first = (0 until n).firstOrNull { a.log[it] != b.log[it] } ?: continue
                for (k in n - 1 downTo first) {
                    if (a.log[k].index == b.log[k].index && a.log[k].term == b.log[k].term) {
                        val key = "${a.id.value}|${b.id.value}|${a.log[k].index}"
                        if (reported.add(key)) {
                            out.add(
                                "${a.id.value} and ${b.id.value} both hold index ${a.log[k].index} " +
                                    "term ${a.log[k].term} but differ at index ${a.log[first].index}",
                            )
                        }
                        break
                    }
                }
            }
        }
        return out
    }
}
