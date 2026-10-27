package io.raftlab.sim

/**
 * The `client-write` scenario: three clients each run 15 operations on their own keys `a` and `b` in a fault-free
 * three-node cluster.
 *
 * Values: [CLIENTS] = 3 clients, [OPS] = 15 operations each, per-client key spaces over `a`, `b`, starting at 300 ms.
 * The leader is awaited within 1 000 ms, then the run lasts 2 000 ms. Expectations: every operation completed,
 * per-client sequential consistency, and all nodes up and agreeing on the key-value data.
 */
internal object ClientWriteScenario {
    /** Number of simulated clients. */
    const val CLIENTS = 3

    /** Number of operations issued by each client. */
    const val OPS = 15

    /** The scenario, named `client-write`. */
    val scenario: Scenario =
        scenario("client-write") {
            nodes(3)
            clients(count = CLIENTS, opsPerClient = OPS, keys = listOf("a", "b"), startAt = 300, perClientKeys = true)
            awaitLeader(within = 1_000)
            runFor(2_000)
            expect("every operation completed") { v ->
                v.history.entries.size == CLIENTS * OPS && v.history.pending().isEmpty()
            }
            expect("own-key reads see own writes") { v -> ScenarioChecks.sequentialPerClient(v.history) }
            expect("all nodes agree") { v -> v.nodes().all { it.up } && ScenarioChecks.kvAgree(v.nodes()) }
        }
}
