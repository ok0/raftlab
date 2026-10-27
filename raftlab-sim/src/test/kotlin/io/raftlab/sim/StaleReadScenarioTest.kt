package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

internal class StaleReadScenarioTest {
    private companion object {
        const val PUT_AT = 500L
        const val PARTITION_AT = 800L
        const val WRITE_AT = 1_400L
        const val STALE_READ_AT = 1_700L
        const val FRESH_READ_AT = 2_000L
        const val HEAL_AT = 2_300L
    }

    private val staleRead: Scenario =
        scenario("stale-read") {
            nodes(5)
            var minority: Set<NodeId> = emptySet()
            var oldLeader: NodeId? = null
            var newLeader: NodeId? = null
            var newTerm = 0L
            var oldRoleAtRead: Role? = null
            var oldTermAtRead = 0L
            var writer = ""
            var staleReader = ""
            var freshReader = ""
            at(PUT_AT) { submitTo(checkNotNull(leader()).id, KvOp.Put("k", "old")) }
            at(PARTITION_AT) {
                minority = isolateLeaderWithMinority(2)
                oldLeader = minority.first()
            }
            at(WRITE_AT) {
                val l = nodes().filter { it.up && it.role == Role.LEADER && it.id !in minority }.maxByOrNull { it.term }
                newLeader = l?.id
                newTerm = l?.term ?: 0L
                if (l != null) writer = submitTo(l.id, KvOp.Put("k", "new"))
            }
            at(STALE_READ_AT) {
                val o = node(checkNotNull(oldLeader))
                oldRoleAtRead = o.role
                oldTermAtRead = o.term
                staleReader = submitTo(o.id, KvOp.Get("k"))
            }
            at(FRESH_READ_AT) {
                newLeader?.let { freshReader = submitTo(it, KvOp.Get("k")) }
            }
            at(HEAL_AT) { heal() }
            awaitLeader(within = 1_000)
            runFor(3_500)
            expect("old leader still leading at stale read") {
                oldRoleAtRead == Role.LEADER && oldTermAtRead < newTerm
            }
            expect("new write acknowledged before stale read") {
                val returned =
                    it.history
                        .ofClient(writer)
                        .singleOrNull()
                        ?.returnedAt
                returned != null && returned < STALE_READ_AT
            }
            expect("old leader never answered Ok") {
                it.history
                    .ofClient(staleReader)
                    .all { e -> e.returnedAt == null }
            }
            expect("old leader ran a confirmation round") {
                it.trace.any { e ->
                    e is MessageSent &&
                        e.from == oldLeader &&
                        e.time >= STALE_READ_AT &&
                        e.kind == MessageKind.APPEND_ENTRIES &&
                        !e.summary.endsWith("readSeq=0")
                }
            }
            expect("fresh read returns new value") {
                val fresh =
                    it.history
                        .ofClient(freshReader)
                        .singleOrNull()
                fresh?.result == KvResult.Ok("new")
            }
        }

    private val unconfirmedLocalReads =
        ClientRequestHook { sim, target, request ->
            val op = request.command.op
            if (op is KvOp.Get && sim.node(target).role == Role.LEADER) {
                KvResult.Ok(sim.kvData(target)?.get(op.key))
            } else {
                null
            }
        }

    @Test
    fun staleReadIsNeverServedForTwentySeeds() {
        for (seed in 1L..20L) {
            val r = staleRead.run(seed)
            withClue(r.report) {
                r.passed shouldBe true
                LinearizabilityChecker.check(r.history).shouldBeInstanceOf<LinResult.Linearizable>()
            }
        }
    }

    @Test
    fun unconfirmedReadDoubleIsRejectedByChecker() {
        for (seed in 1L..20L) {
            val r = staleRead.prepare(seed, requestHook = unconfirmedLocalReads).execute()
            withClue(r.report) {
                r.expectations.single { it.name == "new write acknowledged before stale read" }.passed shouldBe true
                r.expectations.single { it.name == "old leader never answered Ok" }.passed shouldBe false
                LinearizabilityChecker.check(r.history).shouldBeInstanceOf<LinResult.NotLinearizable>()
            }
        }
    }
}
