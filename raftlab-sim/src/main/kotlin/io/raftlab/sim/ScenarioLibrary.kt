package io.raftlab.sim

/**
 * The built-in scenario library, keyed by scenario name in declaration order.
 *
 * It contains `normal-election`, `leader-crash`, `partition-heal`, `minority-partition` and `client-write`; later
 * milestones add more scenarios.
 */
public object ScenarioLibrary {
    /**
     * All library scenarios keyed by name, in the order `normal-election`, `leader-crash`, `partition-heal`,
     * `minority-partition`, `client-write`.
     */
    public val all: Map<String, Scenario> =
        listOf(
            NormalElectionScenario.scenario,
            LeaderCrashScenario.scenario,
            PartitionHealScenario.scenario,
            MinorityPartitionScenario.scenario,
            ClientWriteScenario.scenario,
        ).associateBy { it.name }

    init {
        require(all.size == 5) { "duplicate scenario name" }
    }
}
