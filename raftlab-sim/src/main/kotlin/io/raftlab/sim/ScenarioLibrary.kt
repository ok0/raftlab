package io.raftlab.sim

/**
 * The built-in scenario library, keyed by scenario name in declaration order.
 *
 * It contains `normal-election`, `leader-crash`, `partition-heal`, `minority-partition`, `client-write` and
 * `fuzz-random`; later milestones add more scenarios. `fuzz-random` draws its faults and client load randomly from
 * its seed. `restart-all` uses `StorageKind.Durable` and needs a registered `DurableStorageProvider`.
 */
public object ScenarioLibrary {
    /**
     * All library scenarios keyed by name, in the order `normal-election`, `leader-crash`, `partition-heal`,
     * `minority-partition`, `client-write`, `fuzz-random`, `restart-all`. `restart-all` uses `StorageKind.Durable` and
     * needs a registered `DurableStorageProvider`.
     */
    public val all: Map<String, Scenario> =
        listOf(
            NormalElectionScenario.scenario,
            LeaderCrashScenario.scenario,
            PartitionHealScenario.scenario,
            MinorityPartitionScenario.scenario,
            ClientWriteScenario.scenario,
            FuzzScenario.scenario,
            RestartAllScenario.scenario,
        ).associateBy { it.name }

    init {
        require(all.size == 7) { "duplicate scenario name" }
    }
}
