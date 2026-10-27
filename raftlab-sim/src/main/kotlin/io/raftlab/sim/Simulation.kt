package io.raftlab.sim

import io.raftlab.core.Effect
import io.raftlab.core.InMemoryStorage
import io.raftlab.core.Input
import io.raftlab.core.LogEntry
import io.raftlab.core.RaftMessage
import io.raftlab.core.RaftNode
import io.raftlab.core.RaftStorage
import io.raftlab.core.Snapshot
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvServer
import io.raftlab.kv.KvServerOutput
import io.raftlab.kv.KvStateMachine
import io.raftlab.trace.DropReason
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.Role
import io.raftlab.trace.TraceEvent
import io.raftlab.trace.TraceEventDraft
import io.raftlab.trace.TraceRecorder
import java.util.TreeMap

/**
 * A deterministic discrete-event simulation of a Raft cluster.
 *
 * Virtual time only advances when events are processed. The same seed and options always produce the same trace.
 *
 * @property seed Seed from which all random streams of this run are derived.
 * @property options Cluster and fault configuration of this run.
 */
public class Simulation internal constructor(
    /** Seed from which all random streams of this run are derived. */
    public val seed: Long,
    /** Cluster and fault configuration of this run. */
    public val options: SimOptions,
    private val checker: InvariantChecker,
    private val factory: NodeFactory,
    private val hostKv: Boolean = false,
    private val storageFactory: (NodeId) -> RaftStorage = { _ -> InMemoryStorage() },
    private val reopenStorage: Boolean = false,
) {
    /** Creates a simulation with the RaftNode implementation, the given options and invariant checker. */
    public constructor(
        seed: Long,
        options: SimOptions = SimOptions(),
        checker: InvariantChecker = InvariantChecker.standard(),
    ) : this(seed, options, checker, NodeFactory.RAFT)

    private sealed interface SimEvent {
        data class Tick(
            val id: NodeId,
            val incarnation: Int,
        ) : SimEvent

        data class Deliver(
            val delivery: Delivery,
        ) : SimEvent

        class Action(
            val run: (Simulation) -> Unit,
        ) : SimEvent
    }

    private inner class NodeSlot(
        val id: NodeId,
        val index: Int,
    ) {
        var storage: RaftStorage = storageFactory(id)
        var snapshotCache: Snapshot? = storage.loadSnapshot()
        var stateMachine = RecordingStateMachine()
        val random = SimStreams.node(seed, index)
        var host: HostedNode? = null
        var incarnation = 0
        var server: KvServer? = null
        var kvMachine: KvStateMachine? = null
    }

    private class ClientRoute(
        val endpoint: ClientEndpoint,
        val requestId: Long,
    )

    private val routes = TreeMap<Long, ClientRoute>()

    private var nextWireId = 1L

    /** Current virtual time in ms. */
    public var now: Long = 0
        private set

    internal var isCalm: Boolean = false
        private set

    private val recorder = TraceRecorder { now }

    /** Node ids `n1`..`nN` in index order. */
    public val nodeIds: List<NodeId> = (1..options.nodeCount).map { NodeId("n$it") }

    private val memberSet = nodeIds.toSet()

    private val network = SimNetwork(options.faults, SimStreams.network(seed), recorder, nodeIds)

    private val queue = EventQueue<SimEvent>()

    private val slots = nodeIds.mapIndexed { i, id -> NodeSlot(id, i + 1) }

    private val found = ArrayList<Violation>()

    private var processing = false

    private var nextRequestId = 1L

    init {
        require(!hostKv || factory === NodeFactory.RAFT) { "KV hosting requires the RaftNode factory" }
        for (slot in slots) {
            start(slot)
            val id = slot.id
            recorder.emit { seq, time -> NodeStarted(seq, time, id) }
        }
        for (slot in slots) {
            queue.push(options.tickMillis, SimEvent.Tick(slot.id, 0))
        }
        runChecks()
    }

    /** Snapshot of all events recorded so far, in sequence order. */
    public val trace: List<TraceEvent> get() = recorder.events

    /** Violations found so far, in detection order. */
    public val violations: List<Violation> get() = found.toList()

    /** The first violation found, or `null` when none has been found. */
    public val firstViolation: Violation? get() = found.firstOrNull()

    /**
     * Returns a snapshot of the node with the given id.
     *
     * @param id Node to inspect; must be one of [nodeIds].
     * @return Snapshot of the node at the current time.
     */
    public fun node(id: NodeId): NodeState = snapshot(slot(id))

    /**
     * Returns snapshots of all nodes in index order.
     *
     * @return One snapshot per node at the current time.
     */
    public fun nodes(): List<NodeState> = slots.map { snapshot(it) }

    /**
     * Returns the state machine of the node's current (or last) incarnation.
     *
     * @param id Node to inspect; must be one of [nodeIds].
     * @return The recording state machine of that node's incarnation.
     */
    public fun stateMachine(id: NodeId): RecordingStateMachine = slot(id).stateMachine

    /**
     * Proposes a command to the running leader with the highest term, if any.
     *
     * Usable directly or inside [at] actions. Records no trace event and draws no random numbers.
     *
     * @param command Command bytes to propose.
     * @return The request id assigned to the proposal, or `null` when no node is leader.
     */
    public fun propose(command: ByteArray): Long? {
        check(!hostKv) { "propose is not available while hosting KV; send client requests instead" }
        val leader =
            slots
                .filter { it.host?.role == Role.LEADER }
                .maxByOrNull { it.host?.currentTerm ?: 0L } ?: return null
        val host = checkNotNull(leader.host)
        val requestId = nextRequestId++
        route(leader, host.step(Input.Propose(requestId, command.copyOf())))
        afterControl()
        return requestId
    }

    /**
     * Crashes a running node, discarding its volatile state and pending timers.
     *
     * @param id Node to crash; must be running.
     */
    public fun crash(id: NodeId) {
        val s = slot(id)
        check(s.host != null) { "node ${id.value} is already crashed" }
        s.snapshotCache = s.storage.loadSnapshot()
        s.host = null
        s.server = null
        s.kvMachine = null
        s.incarnation += 1
        recorder.emit { seq, time -> NodeCrashed(seq, time, id) }
        afterControl()
    }

    /**
     * Restarts a crashed node from its persisted storage.
     *
     * When the run reopens storage, a new storage instance is opened from the node's persisted files first.
     *
     * @param id Node to restart; must be crashed.
     */
    public fun restart(id: NodeId) {
        val s = slot(id)
        check(s.host == null) { "node ${id.value} is not crashed" }
        if (reopenStorage) s.storage = storageFactory(id)
        s.snapshotCache = s.storage.loadSnapshot()
        start(s)
        recorder.emit { seq, time -> NodeRestarted(seq, time, id) }
        queue.push((now / options.tickMillis + 1) * options.tickMillis, SimEvent.Tick(id, s.incarnation))
        afterControl()
    }

    /**
     * Partitions the network into the given groups; unlisted nodes form one shared group.
     *
     * @param groups Disjoint, non-empty groups of node ids.
     */
    public fun partition(vararg groups: Set<NodeId>) {
        network.partition(groups.toList())
        afterControl()
    }

    /** Removes any network partition. */
    public fun heal() {
        network.heal()
        afterControl()
    }

    /**
     * Schedules an action to run at the given virtual time.
     *
     * @param time Virtual time at which to run [action]; must not be earlier than [now].
     * @param action Action to run with this simulation as argument.
     */
    public fun at(
        time: Long,
        action: (Simulation) -> Unit,
    ) {
        require(time >= now) { "cannot schedule at $time before now=$now" }
        queue.push(time, SimEvent.Action(action))
        afterControl()
    }

    /**
     * Processes all events up to and including [time], then sets [now] to [time].
     *
     * @param time Virtual time to run until; must not be earlier than [now].
     */
    public fun runUntil(time: Long) {
        require(time >= now) { "cannot run until $time before now=$now" }
        while (queue.peekTime()?.let { it <= time } == true) processNext()
        now = time
    }

    /**
     * Advances the simulation by [duration] ms.
     *
     * @param duration Non-negative duration in virtual ms.
     */
    public fun runFor(duration: Long) {
        require(duration >= 0) { "duration must be non-negative: $duration" }
        runUntil(now + duration)
    }

    /**
     * Processes up to [maxEvents] non-stale events, or fewer if the queue runs empty.
     *
     * @param maxEvents Maximum number of events to process; must be non-negative.
     * @return Number of events actually processed.
     */
    public fun runSteps(maxEvents: Int): Int {
        require(maxEvents >= 0) { "maxEvents must be non-negative: $maxEvents" }
        var done = 0
        while (done < maxEvents && queue.size > 0) {
            if (processNext()) done++
        }
        return done
    }

    /**
     * Runs until [stop] returns true after an event, or until [limit] is reached.
     *
     * @param limit Virtual time limit; must not be earlier than [now].
     * @param stop Predicate evaluated before the run and after each processed event.
     * @return `true` if [stop] returned true, `false` if [limit] was reached first.
     */
    public fun runUntil(
        limit: Long,
        stop: (Simulation) -> Boolean,
    ): Boolean {
        require(limit >= now) { "cannot run until $limit before now=$now" }
        if (stop(this)) return true
        while (queue.peekTime()?.let { it <= limit } == true) {
            if (processNext() && stop(this)) return true
        }
        now = limit
        return false
    }

    private fun slot(id: NodeId): NodeSlot = requireNotNull(slots.firstOrNull { it.id == id }) { "unknown node $id" }

    private fun snapshot(s: NodeSlot): NodeState {
        val p = s.storage.loadState()
        val h = s.host
        val snap = currentSnapshot(s)
        return if (h != null) {
            NodeState(
                s.id,
                true,
                h.role,
                h.currentTerm,
                h.leaderHint,
                p.votedFor,
                h.members,
                h.log(),
                h.commitIndex,
                h.lastApplied,
                s.incarnation,
                snap,
            )
        } else {
            NodeState(
                s.id,
                false,
                null,
                p.term,
                null,
                p.votedFor,
                memberSet,
                retainedLog(s.storage, snap?.meta?.lastIndex ?: 0L),
                0,
                0,
                s.incarnation,
                snap,
            )
        }
    }

    private fun currentSnapshot(s: NodeSlot): Snapshot? {
        val host = s.host
        if (host != null && (s.snapshotCache?.meta?.lastIndex ?: 0L) != host.snapshotIndex) {
            s.snapshotCache = if (host.snapshotIndex == 0L) null else s.storage.loadSnapshot()
        }
        return s.snapshotCache
    }

    private fun retainedLog(
        storage: RaftStorage,
        base: Long,
    ): List<LogEntry> {
        val last = storage.lastIndex()
        return if (last <= base) emptyList() else storage.entries(base + 1, last + 1)
    }

    private fun afterControl() {
        if (!processing) runChecks()
    }

    private fun processNext(): Boolean {
        val e = queue.pop()
        check(e.time >= now) { "event time ${e.time} is before now=$now" }
        now = e.time
        processing = true
        when (val ev = e.payload) {
            is SimEvent.Tick -> {
                val s = slot(ev.id)
                val h = s.host
                if (h == null || ev.incarnation != s.incarnation) {
                    processing = false
                    return false
                }
                route(s, h.step(Input.Tick))
                queue.push(now + options.tickMillis, SimEvent.Tick(ev.id, s.incarnation))
            }

            is SimEvent.Deliver -> {
                val d = ev.delivery
                val s = slot(d.msg.to)
                val h = s.host
                if (h == null) {
                    network.dropped(d, DropReason.CRASHED)
                } else if (!network.connected(d.msg.from, d.msg.to)) {
                    network.dropped(d, DropReason.PARTITION)
                } else {
                    network.delivered(d)
                    route(s, h.step(Input.Receive(d.msg)))
                }
            }

            is SimEvent.Action -> {
                ev.run(this)
            }
        }
        processing = false
        runChecks()
        return true
    }

    private fun start(s: NodeSlot) {
        if (hostKv) {
            val machine = KvStateMachine()
            val node = RaftNode(s.id, memberSet, options.raftConfig, s.storage, machine, s.random, recorder)
            s.kvMachine = machine
            s.server = KvServer(node)
            s.host = RaftNodeHost(node)
        } else {
            s.stateMachine = RecordingStateMachine()
            s.host = factory.create(s.id, memberSet, options.raftConfig, s.storage, s.stateMachine, s.random, recorder)
        }
    }

    private fun route(
        s: NodeSlot,
        effects: List<Effect>,
    ) {
        for (e in effects) {
            if (e is Effect.Send) schedule(network.send(e.msg, now))
        }
        val server = s.server ?: return
        for (out in server.onEffects(effects)) {
            if (out is KvServerOutput.Reply) reply(out.response)
        }
    }

    /**
     * Schedules an action at the given virtual time, without re-checking invariants.
     *
     * @param time Virtual time at which to run [action]; must not be earlier than [now].
     * @param action Action to run with this simulation as argument.
     */
    internal fun post(
        time: Long,
        action: (Simulation) -> Unit,
    ) {
        require(time >= now) { "cannot post at $time before now=$now" }
        queue.push(time, SimEvent.Action(action))
    }

    /**
     * Emits a trace event draft with the current time and next sequence number.
     *
     * @param event Draft to stamp and record.
     */
    internal fun emit(event: TraceEventDraft) {
        recorder.emit(event)
    }

    /**
     * Returns the KV data of a node that hosts KV, or `null` when not hosting KV or crashed.
     *
     * @param id Node to inspect; must be one of [nodeIds].
     * @return Snapshot of the node's KV data, or `null`.
     */
    internal fun kvData(id: NodeId): Map<String, String>? = slot(id).kvMachine?.dataSnapshot()

    /**
     * Sends a client request to a node that hosts KV, arriving after [delay] ms.
     *
     * @param target Node that receives the request; must be one of [nodeIds].
     * @param request Request whose id is echoed in the reply to [from].
     * @param delay Non-negative link delay in virtual ms.
     * @param from Endpoint that receives the reply.
     */
    internal fun clientSend(
        target: NodeId,
        request: ClientRequest,
        delay: Long,
        from: ClientEndpoint,
    ) {
        check(hostKv) { "client requests need a simulation that hosts KV" }
        require(delay >= 0) { "delay must be non-negative: $delay" }
        slot(target)
        post(now + delay) { onClientRequest(target, request, from) }
    }

    private fun onClientRequest(
        target: NodeId,
        request: ClientRequest,
        from: ClientEndpoint,
    ) {
        val server = slot(target).server ?: return
        val wireId = nextWireId++
        routes[wireId] = ClientRoute(from, request.requestId)
        for (out in server.onClientRequest(ClientRequest(wireId, request.command))) {
            when (out) {
                is KvServerOutput.NodeEffect -> {
                    val effect = out.effect
                    if (effect is Effect.Send) schedule(network.send(effect.msg, now))
                }

                is KvServerOutput.Reply -> {
                    reply(out.response)
                }
            }
        }
    }

    private fun reply(response: ClientResponse) {
        val r = routes.remove(response.requestId) ?: return
        val answer = ClientResponse(r.requestId, response.result)
        for (d in r.endpoint.replyDelays()) post(now + d) { r.endpoint.receive(answer) }
    }

    /**
     * Stops random network and client-link faults from now on; partitions are unaffected.
     */
    internal fun calm() {
        isCalm = true
        network.calm()
    }

    private fun schedule(sent: Sent) {
        for (d in sent.deliveries) {
            queue.push(d.at, SimEvent.Deliver(d))
        }
    }

    /**
     * Sends a message on the same path as node sends, for tests.
     *
     * @param msg Message to send.
     * @return The id assigned to the message.
     */
    internal fun send(msg: RaftMessage): Long {
        val sent = network.send(msg, now)
        schedule(sent)
        return sent.msgId
    }

    private fun runChecks() {
        found += checker.check(seed, now, nodes())
    }
}
