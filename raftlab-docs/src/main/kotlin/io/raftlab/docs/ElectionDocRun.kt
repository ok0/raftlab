package io.raftlab.docs

import io.raftlab.sim.SimOptions
import io.raftlab.sim.Simulation
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceEvent

/**
 * A docs-internal normal-election run.
 *
 * The window runs from 0 ms until the first leader has received a delivered AppendEntriesResponse from every follower to its first AppendEntries round.
 *
 * @property seed Seed of the run.
 * @property leader The first leader elected in the run.
 * @property events Trace of the run in sequence order.
 * @property window Inclusive time range of the run shown in the docs.
 */
internal class ElectionDocRun(
    val seed: Long,
    val leader: NodeId,
    val events: List<TraceEvent>,
    val window: LongRange,
) {
    companion object {
        /**
         * Runs the normal election with [options] for [seed] and returns the docs window.
         *
         * The default [SimOptions] use the default `RaftConfig`, so pre-vote is on.
         */
        fun normalElection(
            seed: Long,
            timeLimit: Long = 5_000,
            options: SimOptions = SimOptions(),
        ): ElectionDocRun {
            val sim = Simulation(seed, options)
            val elected =
                sim.runUntil(timeLimit) { s ->
                    s.nodes().any { it.up && it.role == Role.LEADER }
                }
            if (!elected) throw IllegalStateException("no leader within $timeLimit ms for seed $seed")
            val leader = sim.nodes().first { it.role == Role.LEADER }.id
            val followers = sim.nodeIds.filter { it != leader }.toSet()
            val done =
                sim.runUntil(timeLimit) { s ->
                    heartbeatRoundComplete(s.trace, leader, followers)
                }
            if (!done) {
                throw IllegalStateException(
                    "first AppendEntries round of ${leader.value} not answered within $timeLimit ms for seed $seed",
                )
            }
            return ElectionDocRun(seed, leader, sim.trace.toList(), 0L..sim.now)
        }

        private fun heartbeatRoundComplete(
            trace: List<TraceEvent>,
            leader: NodeId,
            followers: Set<NodeId>,
        ): Boolean {
            val sends = trace.filterIsInstance<MessageSent>()
            val first =
                sends
                    .filter { it.from == leader && it.kind == MessageKind.APPEND_ENTRIES }
                    .minByOrNull { it.seq }
                    ?: return false
            val delivered = trace.filterIsInstance<MessageDelivered>().map { it.msgId }.toSet()
            val answered =
                sends
                    .filter {
                        it.kind == MessageKind.APPEND_ENTRIES_RESPONSE &&
                            it.to == leader &&
                            it.seq > first.seq &&
                            it.msgId in delivered
                    }.map { it.from }
                    .toSet()
            return answered.containsAll(followers)
        }
    }
}
