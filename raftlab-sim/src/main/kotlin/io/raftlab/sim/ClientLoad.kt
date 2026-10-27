package io.raftlab.sim

/**
 * Load profile of the simulated clients of one scenario.
 *
 * Each client issues [opsPerClient] operations over [keys]; operations are drawn by `ClientOps` with a 40/30/15/15
 * mix of Put, Get, Delete and Cas. The delay of each client link message is uniform in `[minDelay, maxDelay]`
 * virtual ms, drawn from the client's own random stream.
 *
 * `startAt` is the virtual time of the first invocation, `thinkTime` the pause between a return and the next
 * invocation, and `timeout` the delay after which an unanswered request is resent with the same sequence number.
 */
internal data class ClientLoad(
    val count: Int,
    val opsPerClient: Int,
    val keys: List<String>,
    val startAt: Long,
    val thinkTime: Long,
    val timeout: Long,
    val perClientKeys: Boolean,
    val minDelay: Long = 1,
    val maxDelay: Long = 5,
)
