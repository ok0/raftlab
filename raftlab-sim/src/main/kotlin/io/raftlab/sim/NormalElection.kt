package io.raftlab.sim

import io.raftlab.trace.Role

/**
 * Runs a simulation until a leader is elected or [timeLimit] is reached.
 *
 * With default [SimOptions] this gives a 5-node cluster.
 *
 * @param seed Seed of the run.
 * @param options Cluster and fault configuration of the run.
 * @param timeLimit Virtual time limit in ms.
 * @return The result of the run.
 */
public fun runNormalElection(
    seed: Long,
    options: SimOptions = SimOptions(),
    timeLimit: Long = 5_000,
): ElectionRunResult {
    val sim = Simulation(seed, options)
    val ok = sim.runUntil(timeLimit) { s -> s.nodes().any { it.up && it.role == Role.LEADER } }
    val leader = sim.nodes().firstOrNull { it.role == Role.LEADER }?.id
    val electedAt = if (ok) sim.now else null
    return ElectionRunResult(seed, leader, electedAt, sim.trace, sim.violations, sim.nodes())
}
