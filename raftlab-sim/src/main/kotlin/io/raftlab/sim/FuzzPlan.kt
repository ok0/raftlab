package io.raftlab.sim

import io.raftlab.core.SplitMix64
import io.raftlab.trace.NodeId

/**
 * The randomized plan of one `fuzz-random` run, drawn from its seed.
 *
 * All values come from [SimStreams.fuzz], so the same seed always yields the same plan. Partition windows are
 * pairwise disjoint and crash windows of each lane are disjoint; all windows end by [FuzzScenario.FAULT_UNTIL].
 *
 * @property nodes Number of nodes, 3 or 5.
 * @property faults Network faults of the run.
 * @property clientCount Number of simulated clients.
 * @property opsPerClient Number of operations issued by each client.
 * @property keys Keys the clients operate on.
 * @property thinkTime Pause in ms between a return and the next invocation of a client.
 * @property timeout Delay in ms after which an unanswered client request is resent.
 * @property clientFaults Client-link faults of the run.
 * @property partitions Partition windows, in time order.
 * @property crashes Crash windows, lane by lane and in time order within each lane.
 */
internal data class FuzzPlan(
    val nodes: Int,
    val faults: NetworkFaults,
    val clientCount: Int,
    val opsPerClient: Int,
    val keys: List<String>,
    val thinkTime: Long,
    val timeout: Long,
    val clientFaults: ClientLinkFaults,
    val partitions: List<PartitionWindow>,
    val crashes: List<CrashWindow>,
) {
    /** Largest number of nodes that may be down at the same time without losing a majority. */
    val maxDown: Int get() = (nodes - 1) / 2

    /**
     * A network partition from [start] to [end] ms.
     *
     * With [isolateLeader] the current leader is cut off with `size - 1` other nodes; otherwise the nodes of [group]
     * are split from the rest.
     */
    data class PartitionWindow(
        val start: Long,
        val end: Long,
        val isolateLeader: Boolean,
        val size: Int,
        val group: Set<NodeId>,
    )

    /**
     * A crash of one node from [start] to [end] ms.
     *
     * With [leader] the current leader is crashed at [start]; otherwise the node [target] is.
     */
    data class CrashWindow(
        val start: Long,
        val end: Long,
        val leader: Boolean,
        val target: NodeId,
    )

    companion object {
        private val DROP_RATES = listOf(0, 10, 30, 60)
        private val DUPLICATE_RATES = listOf(0, 20, 50)
        private val CLIENT_DROP_RATES = listOf(20, 50, 100)
        private val CLIENT_DUPLICATE_RATES = listOf(0, 20, 50)
        private val CLIENT_EXTRA_DELAYS = listOf(0L, 10L, 40L)

        /** Draws the plan of the run with [seed]. */
        fun draw(seed: Long): FuzzPlan {
            val r = SimStreams.fuzz(seed)
            val nodes = if (r.nextLong(2) == 0L) 3 else 5
            val ids = (1..nodes).map { NodeId("n$it") }
            val f = (nodes - 1) / 2

            val maxDelay = 2 + r.nextLong(14)
            val dropRate = DROP_RATES[r.nextLong(4).toInt()]
            val duplicateRate = DUPLICATE_RATES[r.nextLong(3).toInt()]
            val reorder = r.nextLong(2) == 1L
            val faults = NetworkFaults(1, maxDelay, dropRate, duplicateRate, reorder)

            val clientCount = 2 + r.nextLong(3)
            val opsPerClient = 5 + r.nextLong(16)
            val keys = (1..(1 + r.nextLong(3))).map { "k$it" }
            val thinkTime = r.nextLong(21)
            val timeout = 100 + r.nextLong(201)

            val clientFaults =
                if (r.nextLong(2) == 1L) {
                    ClientLinkFaults(
                        CLIENT_DROP_RATES[r.nextLong(3).toInt()],
                        CLIENT_DUPLICATE_RATES[r.nextLong(3).toInt()],
                        CLIENT_EXTRA_DELAYS[r.nextLong(3).toInt()],
                    )
                } else {
                    ClientLinkFaults()
                }

            val partitions = ArrayList<PartitionWindow>()
            var cursor = FuzzScenario.FAULT_FROM
            while (true) {
                val start = cursor + r.nextLong(500)
                val end = start + 100 + r.nextLong(700)
                if (end > FuzzScenario.FAULT_UNTIL) break
                val isolateLeader = r.nextLong(3) == 0L
                val size = 1 + r.nextLong(f.toLong()).toInt()
                val group = shuffled(ids, r).take(size).toSet()
                partitions += PartitionWindow(start, end, isolateLeader, size, group)
                cursor = end + 1
            }

            val crashes = ArrayList<CrashWindow>()
            repeat(f) {
                var laneCursor = FuzzScenario.FAULT_FROM
                while (true) {
                    val start = laneCursor + r.nextLong(600)
                    val end = start + 50 + r.nextLong(800)
                    if (end > FuzzScenario.FAULT_UNTIL) break
                    val leader = r.nextLong(2) == 0L
                    val target = ids[r.nextLong(nodes.toLong()).toInt()]
                    crashes += CrashWindow(start, end, leader, target)
                    laneCursor = end + 1
                }
            }

            return FuzzPlan(
                nodes = nodes,
                faults = faults,
                clientCount = clientCount.toInt(),
                opsPerClient = opsPerClient.toInt(),
                keys = keys,
                thinkTime = thinkTime,
                timeout = timeout,
                clientFaults = clientFaults,
                partitions = partitions,
                crashes = crashes,
            )
        }

        private fun <T> shuffled(
            items: List<T>,
            r: SplitMix64,
        ): List<T> {
            val out = items.toMutableList()
            for (i in out.lastIndex downTo 1) {
                val j = r.nextLong(i + 1L).toInt()
                val tmp = out[i]
                out[i] = out[j]
                out[j] = tmp
            }
            return out
        }
    }
}
