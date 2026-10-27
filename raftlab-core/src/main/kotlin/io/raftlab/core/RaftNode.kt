package io.raftlab.core

import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceSink
import io.raftlab.trace.VoteCast

/**
 * Pure, single-threaded Raft node implementing leader election and voting.
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

    private var votedFor: NodeId? = null
    private val peers: List<NodeId> = members.filter { it != id }
    private val votesGranted: MutableSet<NodeId> = mutableSetOf()
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
        }
        return out
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

    private fun lastLogTerm(): Long =
        checkNotNull(storage.termAt(storage.lastIndex())) { "storage has no term for its last index" }

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

    private fun onTick(out: MutableList<Effect>) {
        if (role == Role.LEADER) {
            heartbeatElapsed++
            if (heartbeatElapsed >= config.heartbeatTicks) {
                heartbeatElapsed = 0
                broadcastHeartbeat(out)
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
        broadcastHeartbeat(out)
    }

    private fun broadcastHeartbeat(out: MutableList<Effect>) {
        for (peer in peers) {
            out.add(
                Effect.Send(
                    AppendEntries(currentTerm, id, peer, lastLogIndex(), lastLogTerm(), emptyList(), 0, 0),
                ),
            )
        }
    }

    private fun stepDownToHigherTerm(term: Long) {
        currentTerm = term
        votedFor = null
        persist()
        leaderHint = null
        if (role != Role.FOLLOWER) {
            emitRoleChanged(role, Role.FOLLOWER, REASON_HIGHER_TERM)
            role = Role.FOLLOWER
        }
    }

    private fun onReceive(
        msg: RaftMessage,
        out: MutableList<Effect>,
    ) {
        if (msg.to != id || msg.from == id || msg.from !in members) return
        if (msg is RequestVote && msg.preVote) return
        if (msg is RequestVoteResponse && msg.preVote) return
        if (msg.term > currentTerm) stepDownToHigherTerm(msg.term)
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
            else -> Unit
        }
    }

    private fun handleRequestVote(
        msg: RequestVote,
        out: MutableList<Effect>,
    ) {
        val upToDate =
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
        out.add(
            Effect.Send(
                AppendEntriesResponse(currentTerm, id, msg.from, true, msg.prevLogIndex, 0, 0, msg.readSeq),
            ),
        )
    }

    private companion object {
        const val REASON_TIMEOUT = "election timeout"
        const val REASON_WON = "won election"
        const val REASON_HIGHER_TERM = "higher term"
        const val REASON_LEADER = "leader discovered"
    }
}
