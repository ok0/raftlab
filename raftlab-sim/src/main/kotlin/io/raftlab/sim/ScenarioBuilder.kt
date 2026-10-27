package io.raftlab.sim

/**
 * Collects the declarations of one scenario: nodes, storage, network faults, clients, timed actions, steps and
 * expectations.
 *
 * Defaults are 5 nodes, [StorageKind.InMemory], fault-free [NetworkFaults] with a delay of 1..5 ms, and no clients.
 * Every call validates its arguments at once and throws `IllegalArgumentException` with a message prefixed by
 * `scenario '<name>': `.
 *
 * @property name Name of the scenario.
 */
@ScenarioDsl
public class ScenarioBuilder internal constructor(
    /** Name of the scenario. */
    public val name: String,
) {
    private var nodeCount = 5
    private var storageKind: StorageKind = StorageKind.InMemory
    private var networkFaults = NetworkFaults()
    private var load: ClientLoad? = null
    private val timed = ArrayList<ScenarioSpec.TimedAction>()
    private val steps = ArrayList<ScenarioSpec.Step>()
    private val expectations = ArrayList<ScenarioSpec.Expectation>()

    /**
     * Sets the number of nodes, which must be in `1..9`.
     */
    public fun nodes(n: Int) {
        require(n in 1..9) { "scenario '$name': nodes(n) must be in 1..9: $n" }
        nodeCount = n
    }

    /** Sets how the durable storage of each node is created. */
    public fun storage(kind: StorageKind) {
        storageKind = kind
    }

    /** Sets the network faults of the run. */
    public fun faults(faults: NetworkFaults) {
        networkFaults = faults
    }

    /**
     * Configures the simulated clients; may be called at most once.
     *
     * Each of the [count] clients issues [opsPerClient] operations over [keys], starting at [startAt] ms. A client
     * waits [thinkTime] ms between a return and the next invocation and resends an unanswered request with the same
     * sequence number after [timeout] ms. With [perClientKeys] each client works on its own key space. The client
     * link delay is 1..5 ms and the operation mix is 40/30/15/15 Put/Get/Delete/Cas.
     *
     * Defaults: `startAt = 0`, `thinkTime = 20`, `timeout = 200`, `perClientKeys = false`.
     */
    public fun clients(
        count: Int,
        opsPerClient: Int,
        keys: List<String>,
        startAt: Long = 0,
        thinkTime: Long = 20,
        timeout: Long = 200,
        perClientKeys: Boolean = false,
    ) {
        require(count > 0) { "scenario '$name': clients(count) must be positive: $count" }
        require(opsPerClient > 0) { "scenario '$name': clients(opsPerClient) must be positive: $opsPerClient" }
        require(keys.isNotEmpty()) { "scenario '$name': clients(keys) must not be empty" }
        require(keys.all { it.isNotBlank() }) { "scenario '$name': clients(keys) must not be blank" }
        require(keys.distinct().size == keys.size) { "scenario '$name': clients(keys) must be distinct" }
        require(startAt >= 0) { "scenario '$name': clients(startAt) must be non-negative: $startAt" }
        require(thinkTime >= 0) { "scenario '$name': clients(thinkTime) must be non-negative: $thinkTime" }
        require(timeout > 0) { "scenario '$name': clients(timeout) must be positive: $timeout" }
        require(load == null) { "scenario '$name': clients already configured" }
        load = ClientLoad(count, opsPerClient, keys.toList(), startAt, thinkTime, timeout, perClientKeys)
    }

    /** Runs [action] at virtual time [time] in ms, which must be non-negative. */
    public fun at(
        time: Long,
        action: SimulationView.() -> Unit,
    ) {
        require(time >= 0) { "scenario '$name': at(time) must be non-negative: $time" }
        timed += ScenarioSpec.TimedAction(time, action)
    }

    /**
     * Runs [action] at every time `t = from, from + period, ...` up to and including [until].
     *
     * The period must be positive, [from] non-negative and [until] not before [from]. The occurrences are appended
     * at the position of this declaration, so actions at the same time run in declaration order.
     */
    public fun every(
        period: Long,
        from: Long,
        until: Long,
        action: SimulationView.() -> Unit,
    ) {
        require(period > 0) { "scenario '$name': every(period) must be positive: $period" }
        require(from >= 0) { "scenario '$name': every(from) must be non-negative: $from" }
        require(until >= from) { "scenario '$name': every: until=$until is before from=$from" }
        var t = from
        while (t <= until) {
            timed += ScenarioSpec.TimedAction(t, action)
            t += period
        }
    }

    /**
     * Runs the simulation until a live leader exists, failing the run if none appears within [within] ms.
     *
     * [within] must be positive.
     */
    public fun awaitLeader(within: Long) {
        require(within > 0) { "scenario '$name': awaitLeader(within) must be positive: $within" }
        steps += ScenarioSpec.Step.AwaitLeader(within)
    }

    /** Runs the simulation for [duration] ms of virtual time, which must be positive. */
    public fun runFor(duration: Long) {
        require(duration > 0) { "scenario '$name': runFor(duration) must be positive: $duration" }
        steps += ScenarioSpec.Step.RunFor(duration)
    }

    /**
     * Declares an expectation evaluated after the run; [check] receives the final view and returns whether it holds.
     *
     * The name must be non-blank and unique within the scenario. An exception thrown by [check] fails the expectation.
     */
    public fun expect(
        name: String,
        check: (SimulationView) -> Boolean,
    ) {
        require(name.isNotBlank()) { "scenario '${this.name}': expect(name) must not be blank" }
        require(expectations.none { it.name == name }) { "scenario '${this.name}': duplicate expectation: $name" }
        expectations += ScenarioSpec.Expectation(name, check)
    }

    internal fun build(): ScenarioSpec {
        require(steps.isNotEmpty()) { "scenario '$name': has no awaitLeader/runFor step" }
        return ScenarioSpec(
            name,
            nodeCount,
            storageKind,
            networkFaults,
            load,
            timed.toList(),
            steps.toList(),
            expectations.toList(),
        )
    }
}
