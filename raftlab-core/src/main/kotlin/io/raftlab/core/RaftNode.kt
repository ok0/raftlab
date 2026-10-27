package io.raftlab.core

import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.ConfigChanged
import io.raftlab.trace.EntryApplied
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.SnapshotTaken
import io.raftlab.trace.TraceSink
import io.raftlab.trace.VoteCast
import java.util.TreeMap

/**
 * Pure, single-threaded Raft node implementing leader election, voting, log replication, commit, apply, snapshots,
 * InstallSnapshot, single-server membership change, linearizable reads (ReadIndex), a pre-vote phase (config
 * `preVote`) that runs before a real election, and leader stickiness: a node that heard from a leader within
 * `electionTimeoutTicks.first` ticks refuses pre-votes. It also supports leadership transfer
 * (`Input.TransferLeadership`), which hands leadership to a chosen voting member.
 *
 * All behavior is driven by [step]. The node performs no I/O, reads no clock and starts no threads, so the same
 * inputs always produce the same effects.
 *
 * @param initialMembers configuration used until the log or a snapshot provides one; must not be empty; may exclude
 * [id] for a joining server, which then never campaigns.
 */
public class RaftNode(
    /** This node's id. */
    public val id: NodeId,
    initialMembers: Set<NodeId>,
    private val config: RaftConfig,
    private val storage: RaftStorage,
    private val stateMachine: StateMachine,
    private val random: RandomSource,
    private val trace: TraceSink,
) {
    /**
     * Effective configuration, sorted by id: the latest `Config` entry in the retained log, else the snapshot's
     * members, else `initialMembers`. Changes when a `Config` entry is appended or truncated, a snapshot is installed,
     * or on start.
     */
    public var members: Set<NodeId> = initialMembers.sorted().toSet()
        private set

    /** Current role of this node. */
    public var role: Role = Role.FOLLOWER
        private set

    /** Latest term this node has seen. */
    public var currentTerm: Long
        private set

    /** Id of the last known leader, or null when unknown. */
    public var leaderHint: NodeId? = null
        private set

    /**
     * Highest index known to be committed; starts at the snapshot index (0 without a snapshot) on every start and
     * never decreases while running.
     */
    public var commitIndex: Long = 0
        private set

    /**
     * Highest index applied to the state machine; starts at the snapshot index (0 without a snapshot) on every start
     * and never decreases while running. Never exceeds [commitIndex].
     */
    public var lastApplied: Long = 0
        private set

    /** Last index covered by this node's snapshot, or 0 without one; the retained log starts at `snapshotIndex + 1`. */
    public var snapshotIndex: Long = 0
        private set

    private var votedFor: NodeId? = null
    private var peers: List<NodeId> = members.filter { it != id }
    private val votesGranted: MutableSet<NodeId> = mutableSetOf()
    private val nextIndex: MutableMap<NodeId, Long> = TreeMap()
    private val matchIndex: MutableMap<NodeId, Long> = TreeMap()
    private val pending: TreeMap<Long, Long> = TreeMap()
    private var snapshotTerm: Long = 0
    private var ticks: Long = 0
    private val snapshotSentAt: MutableMap<NodeId, Long> = TreeMap()
    private var electionElapsed: Int = 0
    private var electionTimeout: Int = 0
    private var heartbeatElapsed: Int = 0
    private val baseMembers: Set<NodeId> = initialMembers.sorted().toSet()
    private var snapshotMembers: Set<NodeId>? = null
    private var configIndex: Long = 0
    private var termStartIndex: Long = Long.MAX_VALUE
    private var catchUp: CatchUp? = null
    private var readSeq: Long = 0
    private val pendingReads: MutableList<PendingRead> = ArrayList()
    private val readAcks: MutableMap<NodeId, Long> = TreeMap()
    private var heardLeaderAt: Long? = null
    private var transfer: Transfer? = null

    init {
        require(initialMembers.isNotEmpty()) { "initialMembers must not be empty" }
        val s = storage.loadState()
        currentTerm = s.term
        votedFor = s.votedFor
        val snap = storage.loadSnapshot()
        if (snap != null) {
            storage.compactTo(snap.meta.lastIndex)
            stateMachine.restore(snap.data)
            snapshotIndex = snap.meta.lastIndex
            snapshotTerm = snap.meta.lastTerm
            commitIndex = snapshotIndex
            lastApplied = snapshotIndex
            val snapSet = snap.meta.members
            snapshotMembers = snapSet.sorted().toSet()
        }
        recomputeMembers()
        resetElectionTimer()
    }

    /** Id of the target of an in-progress catch-up of a server being added, or null when none is in progress. */
    internal val catchUpTarget: NodeId?
        get() = catchUp?.target

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
            is Input.ChangeMembership -> onChangeMembership(input, out)
            is Input.ReadIndex -> onReadIndex(input, out)
            is Input.TransferLeadership -> onTransferLeadership(input, out)
        }
        return out
    }

    /**
     * Returns a copy of the retained log (`snapshotIndex + 1` through the last index), for invariant checks and tests.
     */
    public fun logSnapshotForChecks(): List<LogEntry> {
        val last = storage.lastIndex()
        return if (last <= snapshotIndex) emptyList() else storage.entries(snapshotIndex + 1, last + 1)
    }

    internal fun nextIndexOf(peer: NodeId): Long? = if (role == Role.LEADER) nextIndex[peer] else null

    internal fun matchIndexOf(peer: NodeId): Long? = if (role == Role.LEADER) matchIndex[peer] else null

    private fun setMembers(next: Set<NodeId>) {
        val sorted = next.sorted().toSet()
        if (sorted == members) return
        members = sorted
        peers = members.filter { it != id }
        if (role == Role.LEADER) {
            for (p in peers) {
                nextIndex.putIfAbsent(p, lastLogIndex() + 1)
                matchIndex.putIfAbsent(p, 0L)
            }
        }
        val node = id
        val list = sorted.toList()
        trace.emit { seq, time -> ConfigChanged(seq, time, node, list) }
    }

    private fun recomputeMembers() {
        val last = logSnapshotForChecks().lastOrNull { it.payload is EntryPayload.Config }
        configIndex = last?.index ?: 0L
        setMembers((last?.payload as EntryPayload.Config?)?.members ?: snapshotMembers ?: baseMembers)
    }

    private fun membersAt(index: Long): Set<NodeId> {
        if (configIndex <= index) return members
        val entries = if (index <= snapshotIndex) emptyList() else storage.entries(snapshotIndex + 1, index + 1)
        val cfg = entries.lastOrNull { it.payload is EntryPayload.Config }
        return (cfg?.payload as EntryPayload.Config?)?.members?.sorted()?.toSet() ?: snapshotMembers ?: baseMembers
    }

    private fun replicationTargets(): List<NodeId> {
        val target = catchUp?.target ?: return peers
        return if (target in peers) peers else peers + target
    }

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
        val cfg = entries.lastOrNull { it.payload is EntryPayload.Config }
        if (cfg != null) {
            configIndex = cfg.index
            setMembers((cfg.payload as EntryPayload.Config).members)
        }
    }

    private fun configSummary(members: Set<NodeId>): String =
        "config members=" + members.sorted().joinToString(",") { it.value }

    private fun truncateLog(fromIndex: Long) {
        storage.truncateFrom(fromIndex)
        val node = id
        trace.emit { seq, time -> LogTruncated(seq, time, node, fromIndex) }
        if (fromIndex <= configIndex) recomputeMembers()
    }

    private fun firstIndexOfTermAt(index: Long): Long {
        val t = termAt(index)
        val floor = maxOf(1L, snapshotIndex)
        var i = index
        while (i > floor && termAt(i - 1) == t) {
            i--
        }
        return i
    }

    private fun lastIndexOfTerm(term: Long): Long? {
        var i = lastLogIndex()
        val floor = maxOf(1L, snapshotIndex)
        while (i >= floor) {
            val t = termAt(i)
            if (t == term) return i
            if (t < term) return null
            i--
        }
        return null
    }

    private fun onTick(out: MutableList<Effect>) {
        ticks++
        if (role == Role.LEADER) {
            catchUp?.let { c ->
                c.idleTicks++
                if (c.idleTicks >= CATCH_UP_TIMEOUTS * config.electionTimeoutTicks.last) {
                    abandonCatchUp(Rejection.Unknown, out)
                }
            }
            transfer?.let { t ->
                if (ticks - t.startedAt >= config.electionTimeoutTicks.last) abandonTransfer(Rejection.Unknown, out)
            }
            heartbeatElapsed++
            if (heartbeatElapsed >= config.heartbeatTicks) {
                heartbeatElapsed = 0
                broadcastAppendEntries(out)
            }
        } else {
            electionElapsed++
            if (electionElapsed >= electionTimeout && id in members) {
                if (config.preVote) startPreVote(out) else startElection(REASON_TIMEOUT, out)
            }
        }
    }

    /**
     * Runs a pre-vote round: asks every peer whether it would vote for this node in the next term, without changing
     * the term or the vote. A single-member cluster starts a real election directly.
     */
    private fun startPreVote(out: MutableList<Effect>) {
        if (members.size == 1) {
            startElection(REASON_TIMEOUT, out)
            return
        }
        val from = role
        role = Role.PRE_CANDIDATE
        leaderHint = null
        votesGranted.clear()
        votesGranted.add(id)
        resetElectionTimer()
        if (from != Role.PRE_CANDIDATE) emitRoleChanged(from, Role.PRE_CANDIDATE, REASON_TIMEOUT)
        val proposed = currentTerm + 1
        for (peer in peers) {
            out.add(Effect.Send(RequestVote(proposed, id, peer, lastLogIndex(), lastLogTerm(), true)))
        }
    }

    /**
     * Starts a new election in the next term. A CANDIDATE that times out again also starts a new election, and its
     * role-changed event (CANDIDATE to CANDIDATE) marks the new term. [reason] is the reason recorded on the
     * role-changed event.
     */
    private fun startElection(
        reason: String,
        out: MutableList<Effect>,
    ) {
        val from = role
        currentTerm += 1
        votedFor = id
        persist()
        role = Role.CANDIDATE
        leaderHint = null
        votesGranted.clear()
        votesGranted.add(id)
        resetElectionTimer()
        emitRoleChanged(from, Role.CANDIDATE, reason)
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

    private fun hasMajority(): Boolean = votesGranted.count { it in members } * 2 > members.size

    private fun becomeLeader(out: MutableList<Effect>) {
        emitRoleChanged(role, Role.LEADER, REASON_WON)
        role = Role.LEADER
        leaderHint = id
        heartbeatElapsed = 0
        electionElapsed = 0
        pending.clear()
        snapshotSentAt.clear()
        catchUp = null
        pendingReads.clear()
        readAcks.clear()
        val next = lastLogIndex() + 1
        for (peer in peers) {
            nextIndex[peer] = next
            matchIndex[peer] = 0
        }
        termStartIndex = next
        appendToLog(listOf(LogEntry(next, currentTerm, EntryPayload.NoOp)))
        advanceLeaderCommit(out)
        broadcastAppendEntries(out)
    }

    private fun broadcastAppendEntries(out: MutableList<Effect>) {
        for (peer in replicationTargets()) {
            sendAppendEntries(peer, out)
        }
    }

    private fun sendAppendEntries(
        peer: NodeId,
        out: MutableList<Effect>,
    ) {
        val last = lastLogIndex()
        val next = nextIndex.getOrPut(peer) { last + 1 }.coerceIn(1L, last + 1)
        if (next <= snapshotIndex) {
            sendInstallSnapshot(peer, out)
            return
        }
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
                    readSeq,
                ),
            ),
        )
    }

    private fun sendInstallSnapshot(
        peer: NodeId,
        out: MutableList<Effect>,
    ) {
        val sentAt = snapshotSentAt[peer]
        if (sentAt != null && ticks - sentAt < config.heartbeatTicks) return
        val snap = checkNotNull(storage.loadSnapshot()) { "no snapshot for index $snapshotIndex" }
        snapshotSentAt[peer] = ticks
        out.add(
            Effect.Send(
                InstallSnapshot(
                    currentTerm,
                    id,
                    peer,
                    snap.meta.lastIndex,
                    snap.meta.lastTerm,
                    snap.meta.members,
                    snap.data,
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

    private fun replyInstall(
        msg: InstallSnapshot,
        lastIndex: Long,
        out: MutableList<Effect>,
    ) {
        out.add(Effect.Send(InstallSnapshotResponse(currentTerm, id, msg.from, lastIndex)))
    }

    // Raft §5.4.2 (Figure 8): only current-term entries commit by counting replicas. With
    // COMMIT_PREVIOUS_TERM_ENTRIES enabled, earlier-term entries commit by count too.
    private fun isCommittable(index: Long): Boolean =
        Mutation.COMMIT_PREVIOUS_TERM_ENTRIES in config.mutations || termAt(index) == currentTerm

    private fun advanceLeaderCommit(out: MutableList<Effect>) {
        if (role != Role.LEADER || members.isEmpty()) return
        val sorted = members.map { if (it == id) lastLogIndex() else matchIndex[it] ?: 0L }.sortedDescending()
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
        if (role == Role.LEADER && id !in members && configIndex <= commitIndex) stepDownRemoved(out)
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

                EntryPayload.NoOp -> {
                    lastApplied = entry.index
                    trace.emit { seq, time -> EntryApplied(seq, time, node, entry.index) }
                }

                is EntryPayload.Config -> {
                    lastApplied = entry.index
                    trace.emit { seq, time -> EntryApplied(seq, time, node, entry.index) }
                    pending.remove(entry.index)?.let { out.add(Effect.Applied(it, entry.index, ByteArray(0))) }
                }
            }
        }
        maybeTakeSnapshot()
    }

    private fun maybeTakeSnapshot() {
        if (lastApplied - snapshotIndex <= config.snapshotThreshold) return
        val index = lastApplied
        val term = termAt(index)
        val snapMembers = membersAt(index)
        storage.saveSnapshot(SnapshotMeta(index, term, snapMembers), stateMachine.snapshot())
        storage.compactTo(index)
        snapshotIndex = index
        snapshotTerm = term
        snapshotMembers = snapMembers
        if (configIndex <= index) configIndex = 0
        val node = id
        trace.emit { seq, time -> SnapshotTaken(seq, time, node, index, term) }
    }

    private fun abandonPending(out: MutableList<Effect>) {
        for (requestId in pending.values) {
            out.add(Effect.Rejected(requestId, Rejection.Unknown))
        }
        pending.clear()
    }

    private fun onReadIndex(
        input: Input.ReadIndex,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) {
            out.add(Effect.Rejected(input.requestId, Rejection.NotLeader(leaderHint)))
            return
        }
        if (commitIndex < termStartIndex) {
            out.add(Effect.Rejected(input.requestId, Rejection.Busy))
            return
        }
        readSeq++
        pendingReads.add(PendingRead(input.requestId, commitIndex, readSeq))
        confirmReads(out)
        if (pendingReads.isNotEmpty()) broadcastAppendEntries(out)
    }

    private fun confirmReads(out: MutableList<Effect>) {
        if (role != Role.LEADER || pendingReads.isEmpty()) return
        val iter = pendingReads.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            if (!hasReadQuorum(r.seq) || lastApplied < r.readIndex) continue
            iter.remove()
            out.add(Effect.ReadReady(r.requestId, r.readIndex))
        }
    }

    private fun hasReadQuorum(seq: Long): Boolean =
        members.count { it == id || (readAcks[it] ?: 0L) >= seq } * 2 > members.size

    private fun abandonReads(out: MutableList<Effect>) {
        for (r in pendingReads) {
            out.add(Effect.Rejected(r.requestId, Rejection.NotLeader(leaderHint)))
        }
        pendingReads.clear()
    }

    private fun onPropose(
        input: Input.Propose,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) {
            out.add(Effect.Rejected(input.requestId, Rejection.NotLeader(leaderHint)))
            return
        }
        if (transfer != null) {
            out.add(Effect.Rejected(input.requestId, Rejection.Busy))
            return
        }
        val entry = LogEntry(lastLogIndex() + 1, currentTerm, EntryPayload.Command(input.command.copyOf()))
        appendToLog(listOf(entry))
        pending[entry.index] = input.requestId
        advanceLeaderCommit(out)
        broadcastAppendEntries(out)
    }

    private fun onChangeMembership(
        input: Input.ChangeMembership,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) {
            out.add(Effect.Rejected(input.requestId, Rejection.NotLeader(leaderHint)))
            return
        }
        if (transfer != null || configIndex > commitIndex || catchUp != null || commitIndex < termStartIndex) {
            out.add(Effect.Rejected(input.requestId, Rejection.Busy))
            return
        }
        val add = input.add
        val remove = input.remove
        val valid =
            (add == null) != (remove == null) &&
                (add == null || add !in members) &&
                (remove == null || (remove in members && members.size > 1))
        if (!valid) {
            out.add(Effect.Rejected(input.requestId, Rejection.Unknown))
            return
        }
        if (remove != null) {
            appendConfig(members - remove, input.requestId, out)
            return
        }
        val target = checkNotNull(add) { "validated add" }
        catchUp = CatchUp(input.requestId, target, commitIndex)
        nextIndex[target] = lastLogIndex() + 1
        matchIndex[target] = 0L
        snapshotSentAt.remove(target)
        sendAppendEntries(target, out)
    }

    private fun appendConfig(
        next: Set<NodeId>,
        requestId: Long,
        out: MutableList<Effect>,
    ) {
        val entry = LogEntry(lastLogIndex() + 1, currentTerm, EntryPayload.Config(next.sorted().toSet()))
        appendToLog(listOf(entry))
        pending[entry.index] = requestId
        advanceLeaderCommit(out)
        if (role == Role.LEADER) broadcastAppendEntries(out)
    }

    private fun progressCatchUp(
        peer: NodeId,
        out: MutableList<Effect>,
    ): Boolean {
        val c = catchUp ?: return false
        if (c.target != peer) return false
        val m = matchIndex[peer] ?: 0L
        if (m > c.lastMatch) {
            c.lastMatch = m
            c.idleTicks = 0
        }
        if (m < c.goal) return false
        catchUp = null
        appendConfig(members + peer, c.requestId, out)
        return true
    }

    private fun abandonCatchUp(
        reason: Rejection,
        out: MutableList<Effect>,
    ) {
        val c = catchUp ?: return
        catchUp = null
        if (c.target !in members) {
            nextIndex.remove(c.target)
            matchIndex.remove(c.target)
            snapshotSentAt.remove(c.target)
        }
        out.add(Effect.Rejected(c.requestId, reason))
    }

    private fun stepDownRemoved(out: MutableList<Effect>) {
        emitRoleChanged(Role.LEADER, Role.FOLLOWER, REASON_REMOVED)
        role = Role.FOLLOWER
        leaderHint = null
        abandonPending(out)
        abandonReads(out)
        abandonTransfer(Rejection.NotLeader(null), out)
    }

    private fun stepDownToHigherTerm(
        term: Long,
        out: MutableList<Effect>,
        transferHint: NodeId? = null,
    ) {
        currentTerm = term
        votedFor = null
        persist()
        leaderHint = null
        if (role != Role.FOLLOWER) {
            val wasLeader = role == Role.LEADER
            emitRoleChanged(role, Role.FOLLOWER, REASON_HIGHER_TERM)
            role = Role.FOLLOWER
            if (wasLeader) {
                abandonPending(out)
                abandonReads(out)
                abandonCatchUp(Rejection.NotLeader(leaderHint), out)
                abandonTransfer(Rejection.NotLeader(transferHint), out)
            }
        }
    }

    private fun onTransferLeadership(
        input: Input.TransferLeadership,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) {
            out.add(Effect.Rejected(input.requestId, Rejection.NotLeader(leaderHint)))
            return
        }
        val target = input.target
        if (target == id || target !in members || id !in members) {
            out.add(Effect.Rejected(input.requestId, Rejection.Unknown))
            return
        }
        if (transfer != null || catchUp != null || configIndex > commitIndex) {
            out.add(Effect.Rejected(input.requestId, Rejection.Busy))
            return
        }
        transfer = Transfer(input.requestId, target, ticks)
        val node = id
        trace.emit { seq, time -> LeadershipTransfer(seq, time, node, target) }
        if (!maybeSendTimeoutNow(out)) sendAppendEntries(target, out)
    }

    private fun maybeSendTimeoutNow(out: MutableList<Effect>): Boolean {
        val t = transfer ?: return false
        if (role != Role.LEADER || t.timeoutNowSent) return false
        if ((matchIndex[t.target] ?: 0L) != lastLogIndex()) return false
        t.timeoutNowSent = true
        out.add(Effect.Send(TimeoutNow(currentTerm, id, t.target)))
        return true
    }

    private fun abandonTransfer(
        reason: Rejection,
        out: MutableList<Effect>,
    ) {
        val t = transfer ?: return
        transfer = null
        out.add(Effect.Rejected(t.requestId, reason))
    }

    private fun handleTimeoutNow(
        msg: TimeoutNow,
        out: MutableList<Effect>,
    ) {
        if (role == Role.LEADER || msg.from != leaderHint || id !in members) return
        startElection(REASON_TRANSFER, out)
    }

    private fun onReceive(
        msg: RaftMessage,
        out: MutableList<Effect>,
    ) {
        if (msg.to != id || msg.from == id) return
        if (msg !is AppendEntries && msg !is InstallSnapshot && msg.from !in members && msg.from != catchUp?.target) {
            return
        }
        if (msg is RequestVote && msg.preVote) {
            if (config.preVote) handlePreVoteRequest(msg, out)
            return
        }
        if (msg is RequestVoteResponse && msg.preVote) {
            if (config.preVote) handlePreVoteResponse(msg, out)
            return
        }
        if (msg.term > currentTerm) {
            stepDownToHigherTerm(msg.term, out, if (msg is AppendEntries || msg is InstallSnapshot) msg.from else null)
        }
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

                is InstallSnapshot -> {
                    out.add(Effect.Send(InstallSnapshotResponse(currentTerm, id, msg.from, 0)))
                }

                else -> {}
            }
            return
        }
        when (msg) {
            is RequestVote -> {
                handleRequestVote(msg, out)
            }

            is RequestVoteResponse -> {
                handleVoteResponse(msg, out)
            }

            is AppendEntries -> {
                handleAppendEntries(msg, out)
            }

            is AppendEntriesResponse -> {
                handleAppendEntriesResponse(msg, out)
                maybeSendTimeoutNow(out)
            }

            is InstallSnapshot -> {
                handleInstallSnapshot(msg, out)
            }

            is InstallSnapshotResponse -> {
                handleInstallSnapshotResponse(msg, out)
                maybeSendTimeoutNow(out)
            }

            is TimeoutNow -> {
                handleTimeoutNow(msg, out)
            }
        }
    }

    private fun logUpToDate(
        lastIndex: Long,
        lastTerm: Long,
    ): Boolean =
        Mutation.SKIP_LOG_UP_TO_DATE_CHECK in config.mutations ||
            lastTerm > lastLogTerm() ||
            (lastTerm == lastLogTerm() && lastIndex >= lastLogIndex())

    private fun handlePreVoteRequest(
        msg: RequestVote,
        out: MutableList<Effect>,
    ) {
        val heardRecently = heardLeaderAt?.let { ticks - it < config.electionTimeoutTicks.first } == true
        val leaderRecent = role == Role.LEADER || heardRecently
        val grant =
            msg.from in members &&
                msg.term > currentTerm &&
                logUpToDate(msg.lastLogIndex, msg.lastLogTerm) &&
                !leaderRecent
        val replyTerm = if (grant) msg.term else currentTerm
        out.add(Effect.Send(RequestVoteResponse(replyTerm, id, msg.from, grant, true)))
    }

    private fun handlePreVoteResponse(
        msg: RequestVoteResponse,
        out: MutableList<Effect>,
    ) {
        if (!msg.granted) {
            if (msg.term > currentTerm) stepDownToHigherTerm(msg.term, out)
            return
        }
        if (role != Role.PRE_CANDIDATE || msg.term != currentTerm + 1) return
        votesGranted.add(msg.from)
        if (hasMajority()) startElection(REASON_PRE_VOTE, out)
    }

    private fun handleRequestVote(
        msg: RequestVote,
        out: MutableList<Effect>,
    ) {
        val upToDate = logUpToDate(msg.lastLogIndex, msg.lastLogTerm)
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
        if (role == Role.CANDIDATE || role == Role.PRE_CANDIDATE) {
            emitRoleChanged(role, Role.FOLLOWER, REASON_LEADER)
            role = Role.FOLLOWER
        }
        leaderHint = msg.from
        heardLeaderAt = ticks
        resetElectionTimer()
        val prev = msg.prevLogIndex
        if (prev > lastLogIndex()) {
            replyAppend(msg, false, 0, lastLogIndex() + 1, 0, out)
            return
        }
        var entries = msg.entries
        if (prev < snapshotIndex) {
            entries = entries.filter { it.index > snapshotIndex }
        } else {
            val localTerm = termAt(prev)
            if (localTerm != msg.prevLogTerm) {
                replyAppend(msg, false, 0, firstIndexOfTermAt(prev), localTerm, out)
                return
            }
        }
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
        val lastNew = maxOf(prev + msg.entries.size, snapshotIndex)
        if (msg.leaderCommit > commitIndex) advanceCommitTo(minOf(msg.leaderCommit, lastNew), out)
        replyAppend(msg, true, lastNew, 0, 0, out)
    }

    private fun handleAppendEntriesResponse(
        msg: AppendEntriesResponse,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) return
        if (msg.readSeq > (readAcks[msg.from] ?: 0L)) readAcks[msg.from] = msg.readSeq
        applyAppendResult(msg, out)
        confirmReads(out)
    }

    private fun applyAppendResult(
        msg: AppendEntriesResponse,
        out: MutableList<Effect>,
    ) {
        val peer = msg.from
        val match = matchIndex[peer] ?: return
        if (msg.success) {
            val advanced = msg.matchIndex > match
            if (advanced) matchIndex[peer] = minOf(msg.matchIndex, lastLogIndex())
            nextIndex[peer] = matchIndex.getValue(peer) + 1
            if (advanced) {
                advanceLeaderCommit(out)
                if (role != Role.LEADER) return
                if (progressCatchUp(peer, out)) return
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

    private fun handleInstallSnapshot(
        msg: InstallSnapshot,
        out: MutableList<Effect>,
    ) {
        if (role == Role.LEADER) return
        if (role == Role.CANDIDATE || role == Role.PRE_CANDIDATE) {
            emitRoleChanged(role, Role.FOLLOWER, REASON_LEADER)
            role = Role.FOLLOWER
        }
        leaderHint = msg.from
        heardLeaderAt = ticks
        resetElectionTimer()
        if (msg.lastIndex <= commitIndex) {
            replyInstall(msg, commitIndex, out)
            return
        }
        val keep = msg.lastIndex <= lastLogIndex() && termAt(msg.lastIndex) == msg.lastTerm
        storage.saveSnapshot(SnapshotMeta(msg.lastIndex, msg.lastTerm, msg.members), msg.data)
        snapshotMembers = msg.members.sorted().toSet()
        if (!keep && lastLogIndex() > snapshotIndex) truncateLog(snapshotIndex + 1)
        storage.compactTo(msg.lastIndex)
        stateMachine.restore(msg.data.copyOf())
        snapshotIndex = msg.lastIndex
        snapshotTerm = msg.lastTerm
        commitIndex = msg.lastIndex
        lastApplied = msg.lastIndex
        recomputeMembers()
        val node = id
        val li = msg.lastIndex
        val lt = msg.lastTerm
        trace.emit { seq, time -> SnapshotInstalled(seq, time, node, li, lt) }
        abandonPending(out)
        replyInstall(msg, msg.lastIndex, out)
    }

    private fun handleInstallSnapshotResponse(
        msg: InstallSnapshotResponse,
        out: MutableList<Effect>,
    ) {
        if (role != Role.LEADER) return
        val peer = msg.from
        val match = matchIndex[peer] ?: return
        val newMatch = minOf(msg.lastIndex, lastLogIndex())
        if (newMatch <= match) return
        snapshotSentAt.remove(peer)
        matchIndex[peer] = newMatch
        nextIndex[peer] = newMatch + 1
        advanceLeaderCommit(out)
        if (role != Role.LEADER) return
        if (progressCatchUp(peer, out)) return
        if (nextIndex.getValue(peer) <= lastLogIndex()) sendAppendEntries(peer, out)
    }

    private class CatchUp(
        val requestId: Long,
        val target: NodeId,
        val goal: Long,
    ) {
        var lastMatch: Long = 0
        var idleTicks: Int = 0
    }

    private class PendingRead(
        val requestId: Long,
        val readIndex: Long,
        val seq: Long,
    )

    private class Transfer(
        val requestId: Long,
        val target: NodeId,
        val startedAt: Long,
    ) {
        var timeoutNowSent: Boolean = false
    }

    private companion object {
        const val REASON_TIMEOUT = "election timeout"
        const val REASON_PRE_VOTE = "pre-vote granted"
        const val REASON_TRANSFER = "leadership transfer"
        const val REASON_WON = "won election"
        const val REASON_HIGHER_TERM = "higher term"
        const val REASON_LEADER = "leader discovered"
        const val REASON_REMOVED = "removed from configuration"
        const val CATCH_UP_TIMEOUTS = 10
    }
}
