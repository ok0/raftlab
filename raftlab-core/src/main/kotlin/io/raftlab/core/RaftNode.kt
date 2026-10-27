package io.raftlab.core

import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.EntryApplied
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceSink
import io.raftlab.trace.VoteCast
import java.util.TreeMap

/**
 * Pure, single-threaded Raft node implementing leader election, voting, log replication, commit and apply.
 *
 * All behavior is driven by [step]. The node performs no I/O, reads no clock and starts no threads, so the same
 * inputs always produce the same effects.
 *
 * @param initialMembers cluster membership; must contain [id].
 */
public class RaftNode(
    /** This node's id; must be in initialMembers. */
    public val id: NodeId,
    initialMembers: Set<NodeId>,
    private val config: RaftConfig,
    private val storage: RaftStorage,
    private val stateMachine: StateMachine,
    private val random: RandomSource,
    private val trace: TraceSink,
) {
    /** Cluster membership, sorted by id. */
    public val members: Set<NodeId> = initialMembers.sorted().toSet()

    /** Current role of this node. */
    public var role: Role = Role.FOLLOWER
        private set

    /** Latest term this node has seen. */
    public var currentTerm: Long
        private set

    /** Id of the last known leader, or null when unknown. */
    public var leaderHint: NodeId? = null
        private set

    /** Highest index known to be committed; starts at 0 on every start and never decreases while running. */
    public var commitIndex: Long = 0
        private set

    /** Highest index applied to the state machine; never exceeds [commitIndex]. */
    public var lastApplied: Long = 0
        private set

    private var votedFor: NodeId? = null
    private val peers: List<NodeId> = members.filter { it != id }
    private val votesGranted: MutableSet<NodeId> = mutableSetOf()
    private val nextIndex: MutableMap<NodeId, Long> = TreeMap()
    private val matchIndex: MutableMap<NodeId, Long> = TreeMap()
    private val pending: TreeMap<Long, Long> = TreeMap()
    private var electionElapsed: Int = 0
    private var electionTimeout: Int = 0
    private var heartbeatElapsed: Int = 0

    init {
        require(id in initialMembers) { "id $id must be a member of $initialMembers" }
        val s = storage.loadState()
        currentTerm = s.term
        votedFor = s.votedFor
        resetElectionTimer()
    }

    /**
     * Advances the node by one [input] and returns the effects the driver must carry out, in order.
     * Deterministic: the same node state and input always produce the same effects.
     */
    public fun step(input: Input): List<Effect> {
        val out = ArrayList<Effect>()
        when (input) {
            Input.Tick -> onTick(out)
            is Input.Receive -> onReceive(input.msg, out)
            is Input.Propose -> onPropose(input, out)
        }
        return out
    }

    /** Returns a copy of the whole log, for invariant checks and tests. */
    public fun logSnapshotForChecks(): List<LogEntry> = storage.entries(1, storage.lastIndex() + 1)

    internal fun nextIndexOf(peer: NodeId): Long? = if (role == Role.LEADER) nextIndex[peer] else null

    internal fun matchIndexOf(peer: NodeId): Long? = if (role == Role.LEADER) matchIndex[peer] else null

    private fun resetElectionTimer() {
        electionElapsed = 0
        val r = config.electionTimeoutTicks
        electionTimeout = r.first + random.nextLong((r.last - r.first + 1).toLong()).toInt()
    }

    private fun persist() {
        storage.saveState(PersistentState(currentTerm, votedFor))
    }

    private fun lastLogIndex(): Long = storage.lastIndex()

    private fun termAt(index: Long): Long =
        checkNotNull(storage.termAt(index)) { "storage has no term for index $index" }

    private fun lastLogTerm(): Long = termAt(lastLogIndex())

    private fun emitRoleChanged(
        from: Role,
        to: Role,
        reason: String,
    ) {
        val node = id
        val term = currentTerm
        trace.emit { seq, time -> RoleChanged(seq, time, node, from, to, term, reason) }
    }

    private fun emitVoteCast(
        candidate: NodeId,
        granted: Boolean,
    ) {
        val node = id
        val term = currentTerm
        trace.emit { seq, time -> VoteCast(seq, time, node, term, candidate, granted) }
    }

    private fun appendToLog(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        storage.append(entries)
        val node = id
        for (entry in entries) {
            val index = entry.index
            val term = entry.term
            val kind =
                when (entry.payload) {
                    EntryPayload.NoOp -> EntryKind.NOOP
                    is EntryPayload.Command -> EntryKind.COMMAND
                    is EntryPayload.Config -> EntryKind.CONFIG
                }
            val summary =
                when (val payload = entry.payload) {
                    EntryPayload.NoOp -> "noop"
                    is EntryPayload.Command -> "command bytes=${payload.bytes.size}"
                    is EntryPayload.Config -> configSummary(payload.members)
                }
            trace.emit { seq, time -> LogAppended(seq, time, node, index, term, kind, summary) }
        }
    }

    private fun configSummary(members: Set<NodeId>): String =
        "config members=" + members.sorted().joinToString(",") { it.value }

    private fun truncateLog(fromIndex: Long) {
        storage.truncateFrom(fromIndex)
        val node = id
        trace.emit { seq, time -> LogTruncated(seq, time, node, fromIndex) }
    }

    private fun firstIndexOfTermAt(index: Long): Long {
        val t = termAt(index)
        var i = index
        while (i > 1 && termAt(i - 1) == t) {
            i--
        }
        return i
    }

    private fun lastIndexOfTerm(term: Long): Long? {
        var i = lastLogIndex()
        while (i >= 1) {
            val t = termAt(i)
            if (t == term) return i
            if (t < term) return null
            i--
        }
        return null
    }

    private fun onTick(out: MutableList<Effect>) {
        if (role == Role.LEADER) {
            heartbeatElapsed++
            if (heartbeatElapsed >= config.heartbeatTicks) {
                heartbeatElapsed = 0
                broadcastAppendEntries(out)
            }
        } else {
            electionElapsed++
            if (electionElapsed >= electionTimeout) startElection(out)
        }
    }

    /**
     * Starts a new election in the next term. A CANDIDATE that times out again also starts a new election, and its
     * role-changed event (CANDIDATE to CANDIDATE) marks the new term.
     */
    private fun startElection(out: MutableList<Effect>) {
        val from = role
        currentTerm += 1
        votedFor = id
        persist()
        role = Role.CANDIDATE
        leaderHint = null
        votesGranted.clear()
        votesGranted.add(id)
        resetElectionTimer()
        emitRoleChanged(from, Role.CANDIDATE, REASON_TIMEOUT)
        if (hasMajority()) {
            becomeLeader(out)
        } else {
            for (peer in peers) {
                out.add(
                    Effect.Send(RequestVote(currentTerm, id, peer, lastLogIndex(), lastLogTerm(), false)),
                )
            }
        }
    }

    private fun hasMajority(): Boolean = votesGranted.size * 2 > members.size

    private fun becomeLeader(out: MutableList<Effect>) {
        emitRoleChanged(role, Role.LEADER, REASON_WON)
        role = Role.LEADER
        leaderHint = id
        heartbeatElapsed = 0
        electionElapsed = 0
        pending.clear()
        val next = lastLogIndex() + 1
        for (peer in peers) {
            nextIndex[peer] = next
            matchIndex[peer] = 0
        }
        appendToLog(listOf(LogEntry(next, currentTerm, EntryPayload.NoOp)))
        advanceLeaderCommit(out)
        broadcastAppendEntries(out)
    }

    private fun broadcastAppendEntries(out: MutableList<Effect>) {
        for (peer in peers) {
            sendAppendEntries(peer, out)
        }
    }

    private fun sendAppendEntries(
        peer: NodeId,
        out: MutableList<Effect>,
    ) {
        val last = lastLogIndex()
        val next = nextIndex.getValue(peer).coerceIn(1L, last + 1)
        val toExclusive = minOf(last + 1, next + config.maxEntriesPerAppend)
        out.add(
            Effect.Send(
                AppendEntries(
                    currentTerm,
                    id,
                    peer,
                    next - 1,
                    termAt(next - 1),
                    storage.entries(next, toExclusive),
                    commitIndex,
                    0,
                ),
            ),
        )
    }

    private fun replyAppend(
        msg: AppendEntries,
        success: Boolean,
        match: Long,
        conflictIndex: Long,
        conflictTerm: Long,
        out: MutableList<Effect>,
    ) {
        out.add(
            Effect.Send(
                AppendEntriesResponse(
                    currentTerm,
                    id,
                    msg.from,
                    success,
                    match,
                    conflictIndex,
                    conflictTerm,
                    msg.readSeq,
                ),
            ),
        )
    }

    // Raft §5.4.2 (Figure 8): only current-term entries commit by counting replicas. With
    // COMMIT_PREVIOUS_TERM_ENTRIES enabled, earlier-term entries commit by count too.
    private fun isCommittable(index: Long): Boolean =
        Mutation.COMMIT_PREVIOUS_TERM_ENTRIES in config.mutations || termAt(index) == currentTerm

    private fun advanceLeaderCommit(out: MutableList<Effect>) {
        if (role != Role.LEADER) return
        val sorted = (peers.map { matchIndex.getValue(it) } + lastLogIndex()).sortedDescending()
        val quorum = members.size / 2
        val position =
            if (Mutation.COMMIT_WITHOUT_MAJORITY in config.mutations) (quorum - 1).coerceAtLeast(0) else quorum
        val n = sorted[position]
        if (n > commitIndex && isCommittable(n)) advanceCommitTo(n, out)
    }

    private fun advanceCommitTo(
        index: Long,
        out: MutableList<Effect>,
    ) {
        if (index <= commitIndex) return
        commitIndex = index
        val node = id
        trace.emit { seq, time -> CommitAdvanced(seq, time, node, index) }
        applyCommitted(out)
    }

    private fun applyCommitted(out: MutableList<Effect>) {
        while (lastApplied < commitIndex) {
            val entry = storage.entries(lastApplied + 1, lastApplied + 2).single()
            val node = id
            when (val payload = entry.payload) {
                is EntryPayload.Command -> {
                    val result = stateMachine.apply(entry.index, payload.bytes.copyOf())
                    lastApplied = entry.index
                    trace.emit { seq, time -> EntryApplied(seq, time, node, entry.index) }
                    out.add(Effect.Applied(pending.remove(entry.index), entry.index, result))
                }

                EntryPayload.NoOp, is EntryPayload.Config -> {
                    lastApplied = entry.index
                    trace.emit { seq, time -> EntryApplied(seq, time, node, entry.index) }
                }
            }
        }
    }

    private fun abandonPending(out: MutableList<Effect>) {
        for (requestId in pending.values) {
            out.add(Effect.Rejected(requestId, Rejection.Unknown))
        }
        pending.clear()
    }

    private fun onPropose(
        input: Input.Propose,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) {
            out.add(Effect.Rejected(input.requestId, Rejection.NotLeader(leaderHint)))
            return
        }
        val entry = LogEntry(lastLogIndex() + 1, currentTerm, EntryPayload.Command(input.command.copyOf()))
        appendToLog(listOf(entry))
        pending[entry.index] = input.requestId
        advanceLeaderCommit(out)
        broadcastAppendEntries(out)
    }

    private fun stepDownToHigherTerm(
        term: Long,
        out: MutableList<Effect>,
    ) {
        currentTerm = term
        votedFor = null
        persist()
        leaderHint = null
        if (role != Role.FOLLOWER) {
            val wasLeader = role == Role.LEADER
            emitRoleChanged(role, Role.FOLLOWER, REASON_HIGHER_TERM)
            role = Role.FOLLOWER
            if (wasLeader) abandonPending(out)
        }
    }

    private fun onReceive(
        msg: RaftMessage,
        out: MutableList<Effect>,
    ) {
        if (msg.to != id || msg.from == id || msg.from !in members) return
        if (msg is RequestVote && msg.preVote) return
        if (msg is RequestVoteResponse && msg.preVote) return
        if (msg.term > currentTerm) stepDownToHigherTerm(msg.term, out)
        if (msg.term < currentTerm) {
            when (msg) {
                is RequestVote -> {
                    emitVoteCast(msg.from, false)
                    out.add(Effect.Send(RequestVoteResponse(currentTerm, id, msg.from, false, false)))
                }

                is AppendEntries -> {
                    out.add(
                        Effect.Send(
                            AppendEntriesResponse(currentTerm, id, msg.from, false, 0, 0, 0, msg.readSeq),
                        ),
                    )
                }

                else -> {}
            }
            return
        }
        when (msg) {
            is RequestVote -> handleRequestVote(msg, out)
            is RequestVoteResponse -> handleVoteResponse(msg, out)
            is AppendEntries -> handleAppendEntries(msg, out)
            is AppendEntriesResponse -> handleAppendEntriesResponse(msg, out)
            else -> Unit
        }
    }

    private fun handleRequestVote(
        msg: RequestVote,
        out: MutableList<Effect>,
    ) {
        val upToDate =
            Mutation.SKIP_LOG_UP_TO_DATE_CHECK in config.mutations ||
                msg.lastLogTerm > lastLogTerm() ||
                (msg.lastLogTerm == lastLogTerm() && msg.lastLogIndex >= lastLogIndex())
        val canVote = votedFor == null || votedFor == msg.from
        val grant = canVote && upToDate
        if (grant) {
            votedFor = msg.from
            persist()
            resetElectionTimer()
        }
        emitVoteCast(msg.from, grant)
        out.add(Effect.Send(RequestVoteResponse(currentTerm, id, msg.from, grant, false)))
    }

    private fun handleVoteResponse(
        msg: RequestVoteResponse,
        out: MutableList<Effect>,
    ) {
        if (role != Role.CANDIDATE || !msg.granted) return
        votesGranted.add(msg.from)
        if (hasMajority()) becomeLeader(out)
    }

    private fun handleAppendEntries(
        msg: AppendEntries,
        out: MutableList<Effect>,
    ) {
        if (role == Role.LEADER) return
        if (role == Role.CANDIDATE) {
            emitRoleChanged(Role.CANDIDATE, Role.FOLLOWER, REASON_LEADER)
            role = Role.FOLLOWER
        }
        leaderHint = msg.from
        resetElectionTimer()
        val prev = msg.prevLogIndex
        if (prev > lastLogIndex()) {
            replyAppend(msg, false, 0, lastLogIndex() + 1, 0, out)
            return
        }
        val localTerm = termAt(prev)
        if (localTerm != msg.prevLogTerm) {
            replyAppend(msg, false, 0, firstIndexOfTermAt(prev), localTerm, out)
            return
        }
        val entries = msg.entries
        var k = 0
        while (k < entries.size) {
            val e = entries[k]
            if (e.index > lastLogIndex()) break
            if (termAt(e.index) != e.term) {
                truncateLog(e.index)
                break
            }
            k++
        }
        appendToLog(entries.drop(k))
        val lastNew = prev + entries.size
        if (msg.leaderCommit > commitIndex) advanceCommitTo(minOf(msg.leaderCommit, lastNew), out)
        replyAppend(msg, true, lastNew, 0, 0, out)
    }

    private fun handleAppendEntriesResponse(
        msg: AppendEntriesResponse,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) return
        val peer = msg.from
        val match = matchIndex[peer] ?: return
        if (msg.success) {
            val advanced = msg.matchIndex > match
            if (advanced) matchIndex[peer] = minOf(msg.matchIndex, lastLogIndex())
            nextIndex[peer] = matchIndex.getValue(peer) + 1
            if (advanced) {
                advanceLeaderCommit(out)
                if (nextIndex.getValue(peer) <= lastLogIndex()) sendAppendEntries(peer, out)
            }
        } else {
            val current = nextIndex.getValue(peer)
            val hinted =
                if (msg.conflictTerm > 0) {
                    lastIndexOfTerm(msg.conflictTerm)?.plus(1) ?: msg.conflictIndex
                } else {
                    msg.conflictIndex
                }
            val target = if (hinted < current) hinted else current - 1
            nextIndex[peer] = maxOf(target, match + 1, 1L)
            sendAppendEntries(peer, out)
        }
    }

    private companion object {
        const val REASON_TIMEOUT = "election timeout"
        const val REASON_WON = "won election"
        const val REASON_HIGHER_TERM = "higher term"
        const val REASON_LEADER = "leader discovered"
    }
}
