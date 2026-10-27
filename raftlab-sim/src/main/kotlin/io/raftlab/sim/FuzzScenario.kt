package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId
import java.util.TreeMap
import java.util.TreeSet

/**
 * The `fuzz-random` scenario: the faults, client load and partitions and crashes of each run are drawn from its seed
 * by [FuzzPlan.draw].
 *
 * Timeline in virtual ms: clients start at [CLIENT_START]; network, client-link and partition and crash faults act
 * between [FAULT_FROM] and [FAULT_UNTIL]; random faults stop at [CALM_AT], when partitions are healed and crashed
 * nodes are restarted; a final write to [FINAL_KEY] is submitted to the leader at [FINAL_WRITE_AT]; the run ends at
 * [END]. The interval from [CALM_AT] to [END] is the settle period in which clients finish and the cluster converges.
 * Expectations: one live leader, all nodes up, all client operations completed, logs and data agree, and the final
 * write committed. The snapshot threshold is drawn too, by [FuzzPlan.draw]. Membership events drawn by [FuzzPlan] add
 * or remove one node at a time; a removing node is never crashed or restarted, and the membership of the cluster
 * must be committed at the end. Transfer events drawn by [FuzzPlan] ask the leader to hand leadership to another
 * member.
 */
internal object FuzzScenario {
    /** Name of the scenario. */
    const val NAME = "fuzz-random"

    /** Virtual time in ms at which the simulated clients start. */
    const val CLIENT_START = 300L

    /** Virtual time in ms from which partitions and crashes may begin. */
    const val FAULT_FROM = 300L

    /** Virtual time in ms by which every partition and crash window has ended. */
    const val FAULT_UNTIL = 2_900L

    /** Virtual time in ms from which random network and client-link faults stop. */
    const val CALM_AT = 3_000L

    /** Virtual time in ms at which the final write is submitted to the leader. */
    const val FINAL_WRITE_AT = 5_000L

    /** Virtual time in ms at which the run ends. */
    const val END = 6_000L

    /** Key of the final write. */
    const val FINAL_KEY = "final"

    /** The scenario, named [NAME]. */
    val scenario: Scenario =
        scenario(NAME) {
            val plan = FuzzPlan.draw(seed)
            val crashed = TreeMap<Int, NodeId>()
            val removing = TreeSet<NodeId>()
            var expected: Set<NodeId> = (1..plan.nodes).map { NodeId("n$it") }.toSortedSet()
            var finalClient: String? = null
            nodes(plan.nodes)
            faults(plan.faults)
            snapshotThreshold(plan.snapshotThreshold)
            clients(
                plan.clientCount,
                plan.opsPerClient,
                plan.keys,
                startAt = CLIENT_START,
                thinkTime = plan.thinkTime,
                timeout = plan.timeout,
            )
            clientFaults(plan.clientFaults)
            calmFrom(CALM_AT)
            for (w in plan.partitions) {
                at(w.start) {
                    if (!w.isolateLeader) {
                        partition(w.group)
                    } else if (leader() != null && nodeIds.size >= 3) {
                        isolateLeaderWithMinority(minOf(w.size, (nodeIds.size - 1) / 2))
                    }
                }
                at(w.end) { heal() }
            }
            plan.crashes.forEachIndexed { i, w ->
                at(w.start) {
                    val target = if (w.leader) leader()?.id else w.target
                    if (target != null && target !in removing && node(target).up &&
                        nodes().count { !it.up } < minOf(plan.maxDown, (expected.size - 1) / 2)
                    ) {
                        crash(target)
                        crashed[i] = target
                    }
                }
                at(w.end) { crashed.remove(i)?.let { if (it !in removing && !node(it).up) restart(it) } }
            }
            for (m in plan.memberships) {
                at(m.at) {
                    if (m.add) {
                        expected = expected + addNode()
                    } else {
                        val l = leader()?.id
                        val target =
                            if (m.removeLeader && l != null && l in expected && l !in removing) {
                                l
                            } else {
                                expected.sorted().filter { it !in removing && node(it).up }.let { c ->
                                    if (c.isEmpty()) null else c[m.pick % c.size]
                                }
                            }
                        if (target != null) {
                            removing += target
                            expected = expected - target
                            removeNode(target)
                        }
                    }
                }
            }
            for (t in plan.transfers) {
                at(t.at) {
                    val l = leader() ?: return@at
                    val c = l.members.filter { it != l.id }.sorted()
                    if (c.isNotEmpty()) transferLeadership(c[t.pick % c.size])
                }
            }
            at(CALM_AT) {
                heal()
                for (id in nodeIds) {
                    if (!node(id).up) restart(id)
                }
            }
            at(FINAL_WRITE_AT) { leader()?.let { finalClient = submitTo(it.id, KvOp.Put(FINAL_KEY, "done")) } }
            runFor(END)
            expect("exactly one leader") { v -> ScenarioChecks.liveLeaders(v.nodes()).size == 1 }
            expect("all nodes up") { v -> v.nodes().all { it.up } }
            expect("all client operations completed") { v -> v.history.pending().isEmpty() }
            expect("logs and data agree") { v ->
                ScenarioChecks.logsConverged(v.nodes()) && ScenarioChecks.kvAgree(v.nodes())
            }
            expect("final write committed") { v ->
                val id = finalClient
                val entry = if (id == null) null else v.history.ofClient(id).singleOrNull()
                entry?.result is KvResult.Ok
            }
            expect("membership changes committed") { v -> ScenarioChecks.membersCommitted(v.nodes(), expected) }
        }
}
