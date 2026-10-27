package io.raftlab.sim

import io.raftlab.core.AppendEntries
import io.raftlab.core.AppendEntriesResponse
import io.raftlab.core.InstallSnapshot
import io.raftlab.core.InstallSnapshotResponse
import io.raftlab.core.RaftMessage
import io.raftlab.core.RandomSource
import io.raftlab.core.RequestVote
import io.raftlab.core.RequestVoteResponse
import io.raftlab.core.TimeoutNow
import io.raftlab.trace.DropReason
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageDuplicated
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.TraceSink

internal data class Delivery(
    val msgId: Long,
    val msg: RaftMessage,
    val at: Long,
)

internal data class Sent(
    val msgId: Long,
    val deliveries: List<Delivery>,
)

internal fun describeMessage(msg: RaftMessage): Pair<MessageKind, String> =
    when (msg) {
        is RequestVote -> {
            Pair(
                MessageKind.REQUEST_VOTE,
                "lastLogIndex=${msg.lastLogIndex} lastLogTerm=${msg.lastLogTerm} preVote=${msg.preVote}",
            )
        }

        is RequestVoteResponse -> {
            Pair(
                MessageKind.REQUEST_VOTE_RESPONSE,
                "granted=${msg.granted} preVote=${msg.preVote}",
            )
        }

        is AppendEntries -> {
            Pair(
                MessageKind.APPEND_ENTRIES,
                "prevLogIndex=${msg.prevLogIndex} prevLogTerm=${msg.prevLogTerm} entries=${msg.entries.size} " +
                    "leaderCommit=${msg.leaderCommit} readSeq=${msg.readSeq}",
            )
        }

        is AppendEntriesResponse -> {
            Pair(
                MessageKind.APPEND_ENTRIES_RESPONSE,
                "success=${msg.success} matchIndex=${msg.matchIndex} conflictIndex=${msg.conflictIndex} " +
                    "conflictTerm=${msg.conflictTerm} readSeq=${msg.readSeq}",
            )
        }

        is InstallSnapshot -> {
            Pair(
                MessageKind.INSTALL_SNAPSHOT,
                "lastIndex=${msg.lastIndex} lastTerm=${msg.lastTerm} " +
                    "members=${msg.members.sorted().joinToString(",") { it.value }} bytes=${msg.data.size}",
            )
        }

        is InstallSnapshotResponse -> {
            Pair(
                MessageKind.INSTALL_SNAPSHOT_RESPONSE,
                "lastIndex=${msg.lastIndex}",
            )
        }

        is TimeoutNow -> {
            Pair(MessageKind.TIMEOUT_NOW, "")
        }
    }

internal class SimNetwork(
    private var faults: NetworkFaults,
    private val random: RandomSource,
    private val trace: TraceSink,
    private val members: List<NodeId>,
) {
    private var nextMsgId = 1L
    private val lastAt = HashMap<Pair<NodeId, NodeId>, Long>()
    private var groupOf: Map<NodeId, Int>? = null

    fun calm() {
        faults = faults.copy(dropRate = 0, duplicateRate = 0, reorder = false)
    }

    fun send(
        msg: RaftMessage,
        now: Long,
    ): Sent {
        val id = nextMsgId++
        val (kind, summary) = describeMessage(msg)
        val from = msg.from
        val to = msg.to
        val term = msg.term
        trace.emit { seq, time -> MessageSent(seq, time, from, id, from, to, kind, term, summary) }
        if (random.nextLong(1000) < faults.dropRate) {
            trace.emit { seq, time -> MessageDropped(seq, time, from, id, DropReason.RANDOM) }
            return Sent(id, emptyList())
        }
        val deliveries = mutableListOf(Delivery(id, msg, deliveryTime(msg, now)))
        if (random.nextLong(1000) < faults.duplicateRate) {
            val copyId = nextMsgId++
            trace.emit { seq, time -> MessageDuplicated(seq, time, from, id, copyId) }
            deliveries.add(Delivery(copyId, msg, deliveryTime(msg, now)))
        }
        return Sent(id, deliveries)
    }

    private fun deliveryTime(
        msg: RaftMessage,
        now: Long,
    ): Long {
        var t = now + faults.minDelay + random.nextLong(faults.maxDelay - faults.minDelay + 1)
        if (!faults.reorder) {
            val link = msg.from to msg.to
            t = maxOf(t, lastAt[link] ?: 0L)
            lastAt[link] = t
        }
        return t
    }

    fun connected(
        from: NodeId,
        to: NodeId,
    ): Boolean = groupOf?.let { it[from] == it[to] } ?: true

    fun partition(groups: List<Set<NodeId>>): List<List<NodeId>> {
        require(groups.isNotEmpty()) { "partition needs at least one group" }
        require(groups.all { it.isNotEmpty() }) { "partition groups must be non-empty" }
        val all = groups.flatten()
        all.forEach { id -> require(id in members) { "unknown node in partition: ${id.value}" } }
        require(all.size == all.toSet().size) { "a node appears in more than one partition group" }
        val listed = groups.map { it.sorted() }
        val unlisted = members.sorted().filter { it !in all }
        val normalized = if (unlisted.isEmpty()) listed else listed + listOf(unlisted)
        groupOf = normalized.flatMapIndexed { index, group -> group.map { it to index } }.toMap()
        trace.emit { seq, time -> PartitionChanged(seq, time, null, normalized) }
        return normalized
    }

    fun heal() {
        groupOf = null
        val all = listOf(members.sorted())
        trace.emit { seq, time -> PartitionChanged(seq, time, null, all) }
    }

    fun delivered(d: Delivery) {
        val to = d.msg.to
        val msgId = d.msgId
        trace.emit { seq, time -> MessageDelivered(seq, time, to, msgId) }
    }

    fun dropped(
        d: Delivery,
        reason: DropReason,
    ) {
        val to = d.msg.to
        val msgId = d.msgId
        trace.emit { seq, time -> MessageDropped(seq, time, to, msgId, reason) }
    }
}
