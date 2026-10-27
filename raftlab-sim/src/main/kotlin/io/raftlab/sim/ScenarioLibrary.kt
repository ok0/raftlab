package io.raftlab.sim

/**
 * The built-in scenario library, keyed by scenario name in declaration order.
 *
 * It contains `normal-election`, `leader-crash`, `partition-heal`, `minority-partition`, `client-write` and
 * `fuzz-random`; later milestones add more scenarios. `fuzz-random` draws its faults and client load randomly from
 * its seed.
 */
public object ScenarioLibrary {
    /**
     * All library scenarios keyed by name, in the order `normal-election`, `leader-crash`, `partition-heal`,
     * `minority-partition`, `client-write`, `fuzz-random`.
     */
    public val all: Map<String, Scenario> =
        listOf(
            NormalElectionScenario.scenario,
            LeaderCrashScenario.scenario,
            PartitionHealScenario.scenario,
            MinorityPartitionScenario.scenario,
            ClientWriteScenario.scenario,
            FuzzScenario.scenario,
        ).associateBy { it.name }

    init {
        require(all.size == 6) { "duplicate scenario name" }
    }
}
