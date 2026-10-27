package io.raftlab.sim

import java.util.TreeSet

/**
 * Checks the Log Matching property: when two live nodes hold an entry with the same index and term,
 * their logs are identical up to that index.
 *
 * Each pair compares only the overlapping retained range, from the higher snapshot index plus one.
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
                val lo = maxOf(a.snapshotIndex, b.snapshotIndex) + 1
                val hi = minOf(a.lastIndex, b.lastIndex)
                if (lo > hi) continue
                val first = (lo..hi).firstOrNull { a.entryAt(it) != b.entryAt(it) } ?: continue
                for (k in hi downTo first) {
                    val x = checkNotNull(a.entryAt(k))
                    val y = checkNotNull(b.entryAt(k))
                    if (x.term == y.term) {
                        val key = "${a.id.value}|${b.id.value}|$k"
                        if (reported.add(key)) {
                            out.add(
                                "${a.id.value} and ${b.id.value} both hold index $k " +
                                    "term ${x.term} but differ at index $first",
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
