package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.core.SplitMix64
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.ClientInvoked
import io.raftlab.trace.ClientReturned
import io.raftlab.trace.NodeId
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

class SimClientTest {
    @Test
    fun opsReproducibleAndIndependentOfFaults() {
        val sc =
            scenario("ops") {
                nodes(3)
                clients(2, 10, listOf("a", "b"))
                runFor(1)
            }
        sc.prepare(7).clients.map { it.ops } shouldBe
            sc.prepare(7, NetworkFaults(1, 30, 200, 200, true)).clients.map { it.ops }

        val ops = sc.prepare(7).clients.map { it.ops }
        (ops[0] != ops[1]) shouldBe true
        (sc.prepare(8).clients.map { it.ops } != ops) shouldBe true

        val load = ClientLoad(2, 10, listOf("a", "b"), 0, 20, 200, false)
        ClientOps.draw("c1", load, SplitMix64(1)) shouldBe ClientOps.draw("c1", load, SplitMix64(1))

        val many = ClientOps.draw("c1", ClientLoad(1, 200, listOf("a", "b"), 0, 20, 200, false), SplitMix64(1))
        many.map { it::class }.toSet().size shouldBe 4
    }

    @Test
    fun invokedOpsArePrefixOfPlanUnderFaults() {
        val sc =
            scenario("ops-run") {
                nodes(3)
                clients(2, 10, listOf("a", "b"))
                runFor(2_000)
            }
        val planned = sc.prepare(7).clients.associate { it.clientId to it.ops }
        val results = listOf(sc.run(7), sc.run(7, NetworkFaults(1, 30, 200, 200, true)))
        for (result in results) {
            for (clientId in listOf("c1", "c2")) {
                val invoked = result.history.ofClient(clientId).map { it.op }
                planned.getValue(clientId).take(invoked.size) shouldBe invoked
            }
        }
    }

    @Test
    fun oneInvokeAtMostOneReturnDespiteRetries() {
        val sc =
            scenario("retries") {
                nodes(3)
                clients(2, 15, listOf("x", "y"), startAt = 300, timeout = 100)
                at(600) { crashLeader() }
                awaitLeader(1_000)
                runFor(2_500)
            }
        val run = sc.prepare(1)
        val r = run.execute()

        val invokeCounts =
            r.trace
                .filterIsInstance<ClientInvoked>()
                .groupingBy { it.clientId to it.opId }
                .eachCount()
        invokeCounts.values.all { it == 1 } shouldBe true
        val returnCounts =
            r.trace
                .filterIsInstance<ClientReturned>()
                .groupingBy { it.clientId to it.opId }
                .eachCount()
        returnCounts.values.all { it <= 1 } shouldBe true
        (run.clients.sumOf { it.attempts.size } > r.history.entries.size) shouldBe true
    }

    @Test
    fun forcedTimeoutRetriesSameSeqAppliedOnce() {
        val sim = kvSim(2)
        sim.runUntil(5000) { it.leader() != null }
        sim.crash(n1)
        val rec = HistoryRecorder(sim)
        val c =
            SimClient(
                "c1",
                listOf(KvOp.Put("k", "v1")),
                ClientLoad(1, 1, listOf("k"), sim.now, 20, 100, false),
                setOf(n1, n2, n3),
                sim,
                SplitMix64(9),
                rec,
            )
        c.start()
        sim.runFor(2_000)

        c.attempts.first().target shouldBe n1
        (c.attempts.size >= 2) shouldBe true
        c.attempts.all { it.request.command.seq == 1L } shouldBe true
        val answered = rec.history()
        answered.entries.single().result shouldBe KvResult.Ok(null)

        val liveViews =
            sim.nodes().filter { it.up }.map { s ->
                NodeView(
                    s.id,
                    s.up,
                    s.role,
                    s.currentTerm,
                    s.leaderHint,
                    s.commitIndex,
                    s.lastApplied,
                    s.log,
                    sim.kvData(s.id) ?: emptyMap(),
                )
            }
        for (view in liveViews) {
            view.kvData shouldBe mapOf("k" to "v1")
        }
        ScenarioChecks.appliedAtMostOnce(liveViews) shouldBe true
    }

    @Test
    fun unansweredOpIsPending() {
        val sim = kvSim(3)
        for (id in listOf(n1, n2, n3)) sim.crash(id)
        val rec = HistoryRecorder(sim)
        val c =
            SimClient(
                "c1",
                listOf(KvOp.Put("k", "v")),
                ClientLoad(1, 1, listOf("k"), 0, 20, 100, false),
                setOf(n1, n2, n3),
                sim,
                SplitMix64(5),
                rec,
            )
        c.start()
        sim.runFor(1_000)

        val entries = rec.history().entries
        entries.size shouldBe 1
        entries.single().pending shouldBe true
        entries.single().result shouldBe null
        sim.trace.filterIsInstance<ClientInvoked>().size shouldBe 1
        sim.trace.filterIsInstance<ClientReturned>().size shouldBe 0
    }

    @Test
    fun clientEventsRoundTripThroughTraceCodec() {
        val r =
            scenario("rt") {
                nodes(3)
                clients(1, 5, listOf("a"))
                awaitLeader(1_000)
                runFor(1_000)
            }.run(1)

        val invoked = r.trace.filterIsInstance<ClientInvoked>()
        (invoked.isNotEmpty()) shouldBe true
        (r.trace.filterIsInstance<ClientReturned>().isNotEmpty()) shouldBe true
        TraceCodec.decode(TraceCodec.encode(r.trace)) shouldBe r.trace
        for (e in invoked) {
            val entry = r.history.entries.single { it.clientId == e.clientId && it.opId == e.opId }
            e.op shouldBe OpText.op(entry.op)
        }
    }

    @Test
    fun opTextFormats() {
        OpText.op(KvOp.Put("k", "v")) shouldBe "Put(k,v)"
        OpText.op(KvOp.Get("k")) shouldBe "Get(k)"
        OpText.op(KvOp.Delete("k")) shouldBe "Delete(k)"
        OpText.op(KvOp.Cas("k", null, "n")) shouldBe "Cas(k,null,n)"
        OpText.result(KvResult.Ok("v")) shouldBe "Ok(v)"
        OpText.result(KvResult.CasFailed(null)) shouldBe "CasFailed(null)"
        OpText.result(KvResult.NotLeader(NodeId("n1"))) shouldBe "NotLeader(n1)"
        OpText.result(KvResult.NotLeader(null)) shouldBe "NotLeader(null)"
        OpText.result(KvResult.Timeout) shouldBe "Timeout"
    }
}
