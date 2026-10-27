package io.raftlab.sim

import io.raftlab.core.RaftConfig

internal class ScenarioSpec(
    val name: String,
    val nodes: Int,
    val storage: StorageKind,
    val faults: NetworkFaults,
    val calmAt: Long?,
    val clients: ClientLoad?,
    val timed: List<TimedAction>,
    val steps: List<Step>,
    val expectations: List<Expectation>,
    val snapshotThreshold: Int = RaftConfig.DEFAULT_SNAPSHOT_THRESHOLD,
) {
    class TimedAction(
        val time: Long,
        val action: SimulationView.() -> Unit,
    )

    sealed interface Step {
        class AwaitLeader(
            val within: Long,
        ) : Step

        class RunFor(
            val duration: Long,
        ) : Step
    }

    class Expectation(
        val name: String,
        val check: (SimulationView) -> Boolean,
    )
}
