package io.raftlab.core

import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceRecorder
import io.raftlab.trace.TraceSink

val n1 = NodeId("n1")
val n2 = NodeId("n2")
val n3 = NodeId("n3")
val n4 = NodeId("n4")
val n5 = NodeId("n5")
val three = setOf(n1, n2, n3)
val five = setOf(n1, n2, n3, n4, n5)

class FixedRandom(
    private val value: Long = 0,
) : RandomSource {
    val bounds = mutableListOf<Long>()

    override fun nextLong(): Long = value

    override fun nextLong(bound: Long): Long {
        bounds += bound
        return value.coerceAtMost(bound - 1)
    }
}

class RecordingStorage(
    private val delegate: InMemoryStorage = InMemoryStorage(),
) : RaftStorage by delegate {
    val saves = mutableListOf<PersistentState>()
    var failSaves = false

    override fun saveState(state: PersistentState) {
        check(!failSaves) { "injected storage failure" }
        saves += state
        delegate.saveState(state)
    }
}

object NoopStateMachine : StateMachine {
    override fun apply(
        index: Long,
        command: ByteArray,
    ): ByteArray = ByteArray(0)

    override fun snapshot(): ByteArray = ByteArray(0)

    override fun restore(data: ByteArray) {}
}

class RecordingStateMachine : StateMachine {
    val applied = mutableListOf<Pair<Long, String>>()

    override fun apply(
        index: Long,
        command: ByteArray,
    ): ByteArray {
        applied += index to command.decodeToString()
        return "r$index".encodeToByteArray()
    }

    override fun snapshot(): ByteArray = ByteArray(0)

    override fun restore(data: ByteArray) {}
}

fun node(
    id: NodeId = n1,
    members: Set<NodeId> = three,
    config: RaftConfig = RaftConfig(),
    storage: RaftStorage = InMemoryStorage(),
    random: RandomSource = FixedRandom(),
    trace: TraceSink = TraceSink { },
    stateMachine: StateMachine = NoopStateMachine,
): RaftNode = RaftNode(id, members, config, storage, stateMachine, random, trace)

fun RaftNode.tick(times: Int = 1): List<Effect> = (1..times).flatMap { step(Input.Tick) }

fun RaftNode.receive(msg: RaftMessage): List<Effect> = step(Input.Receive(msg))

fun List<Effect>.sent(): List<RaftMessage> = filterIsInstance<Effect.Send>().map { it.msg }

fun rv(
    term: Long,
    from: NodeId,
    to: NodeId = n1,
    lastIndex: Long = 0,
    lastTerm: Long = 0,
) = RequestVote(term, from, to, lastIndex, lastTerm, false)

fun rvr(
    term: Long,
    from: NodeId,
    to: NodeId = n1,
    granted: Boolean = true,
) = RequestVoteResponse(term, from, to, granted, false)

fun ae(
    term: Long,
    from: NodeId,
    to: NodeId = n1,
    prevIndex: Long = 0,
    prevTerm: Long = 0,
) = AppendEntries(term, from, to, prevIndex, prevTerm, emptyList(), 0, 0)

fun aeWith(
    term: Long,
    from: NodeId,
    to: NodeId = n1,
    prevIndex: Long = 0,
    prevTerm: Long = 0,
    entries: List<LogEntry> = emptyList(),
    leaderCommit: Long = 0,
) = AppendEntries(term, from, to, prevIndex, prevTerm, entries, leaderCommit, 0)

fun aer(
    term: Long,
    from: NodeId,
    to: NodeId = n1,
    success: Boolean = true,
    matchIndex: Long = 0,
    conflictIndex: Long = 0,
    conflictTerm: Long = 0,
) = AppendEntriesResponse(term, from, to, success, matchIndex, conflictIndex, conflictTerm, 0)

fun noop(
    index: Long,
    term: Long,
) = LogEntry(index, term, EntryPayload.NoOp)

fun cmd(
    index: Long,
    term: Long,
    text: String,
) = LogEntry(index, term, EntryPayload.Command(text.encodeToByteArray()))

fun logWithTerms(vararg terms: Long): List<LogEntry> = terms.mapIndexed { i, t -> cmd(i + 1L, t, "e${i + 1}") }

fun storageWith(
    term: Long,
    entries: List<LogEntry>,
): InMemoryStorage {
    val storage = InMemoryStorage()
    storage.saveState(PersistentState(term, null))
    storage.append(entries)
    return storage
}

fun storageWithLog(vararg terms: Long): InMemoryStorage {
    val storage = InMemoryStorage()
    storage.append(terms.mapIndexed { i, term -> LogEntry(i + 1L, term, EntryPayload.NoOp) })
    return storage
}

fun leaderAtTerm1(
    storage: RaftStorage = InMemoryStorage(),
    trace: TraceSink = TraceSink { },
): RaftNode {
    val leader = node(storage = storage, trace = trace)
    leader.tick(10)
    leader.receive(rvr(1, n2))
    check(leader.role == Role.LEADER)
    return leader
}

fun RaftNode.propose(
    requestId: Long,
    text: String,
): List<Effect> = step(Input.Propose(requestId, text.encodeToByteArray()))

fun RaftNode.winElection(voter: NodeId): List<Effect> = winElectionWith(listOf(voter))

fun RaftNode.winElection(
    first: NodeId,
    second: NodeId,
): List<Effect> = winElectionWith(listOf(first, second))

private fun RaftNode.winElectionWith(voters: List<NodeId>): List<Effect> {
    tick(10)
    var last = emptyList<Effect>()
    for (v in voters) {
        last = receive(rvr(currentTerm, v))
    }
    check(role == Role.LEADER)
    return last
}

fun recorder() = TraceRecorder { 0L }
