package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.core.EntryPayload
import io.raftlab.core.LogEntry
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.KvCodec
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.EntryApplied
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import org.junit.jupiter.api.Test

internal class ReadIndexSimTest {
    private fun MessageSent.readRound() = kind == MessageKind.APPEND_ENTRIES && !summary.endsWith("readSeq=0")

    private fun hasLoggedGet(log: List<LogEntry>): Boolean {
        for (entry in log) {
            val payload = entry.payload
            if (payload !is EntryPayload.Command) continue
            val op =
                try {
                    KvCodec.decodeCommand(payload.bytes).op
                } catch (e: IllegalArgumentException) {
                    continue
                }
            if (op is KvOp.Get) return true
        }
        return false
    }

    @Test
    fun interleavedPutsAndGetsAppendOnlyWrites() {
        val sim = kvSim(1)
        sim.runUntil(5000) { it.leader() != null }
        val leader = checkNotNull(sim.leader()).id
        val ep = RecordingEndpoint()
        val ops =
            listOf(
                KvOp.Put("a", "1"),
                KvOp.Get("a"),
                KvOp.Put("b", "2"),
                KvOp.Get("b"),
                KvOp.Delete("a"),
                KvOp.Get("a"),
                KvOp.Cas("b", "2", "3"),
                KvOp.Get("b"),
            )
        ops.forEachIndexed { i, op ->
            val n = (i + 1).toLong()
            sim.clientSend(leader, ClientRequest(n, KvCommand("t", n, op)), 1, ep)
            sim.runFor(200)
        }

        ep.got.map { it.result } shouldBe
            listOf(
                KvResult.Ok(null),
                KvResult.Ok("1"),
                KvResult.Ok(null),
                KvResult.Ok("2"),
                KvResult.Ok("1"),
                KvResult.Ok(null),
                KvResult.Ok("3"),
                KvResult.Ok("3"),
            )
        val state = sim.node(leader)
        ScenarioChecks.commandsOf(state.log, "t").size shouldBe 4
        ScenarioChecks.commandsOf(state.log, "t").none { it.op is KvOp.Get } shouldBe true
        sim.trace.count { it is LogAppended && it.node == leader && it.entryKind == EntryKind.COMMAND } shouldBe 4
        sim.trace.count { it is EntryApplied && it.node == leader } shouldBe state.lastApplied.toInt()
        state.log.size.toLong() shouldBe state.lastApplied
        sim.trace.any { it is MessageSent && it.from == leader && it.summary.endsWith("readSeq=4") } shouldBe true
        sim.violations shouldBe emptyList()
    }

    @Test
    fun goldenRunsWithoutGetsSendNoReadRounds() {
        val m9 = listOf("normal-election", "leader-crash", "partition-heal", "minority-partition", "client-write")
        val runs = ArrayList<Pair<String, Long>>()
        for (name in m9) for (seed in 1L..3L) runs += name to seed
        for (name in listOf("restart-all", "lagging-follower-snapshot")) for (seed in 1L..3L) runs += name to seed
        for (seed in 0L..19L) {
            if (FuzzPlan.draw(seed).memberships.isEmpty()) runs += "fuzz-random" to seed
        }
        for ((name, seed) in runs) {
            val r = ScenarioLibrary.all.getValue(name).run(seed)
            val gets = r.history.entries.count { it.op is KvOp.Get }
            println("$name $seed gets=$gets")
            withClue("$name seed $seed") {
                if (gets == 0) {
                    r.trace.filterIsInstance<MessageSent>().none { it.readRound() } shouldBe true
                }
                if (name == "normal-election" || name == "lagging-follower-snapshot") {
                    gets shouldBe 0
                }
            }
        }
    }

    @Test
    fun fuzzGetsUseReadIndex() {
        for (seed in 0L..9L) {
            val r = ScenarioLibrary.all.getValue("fuzz-random").run(seed)
            withClue(r.report) {
                r.passed shouldBe true
                if (r.history.completed().any { it.op is KvOp.Get }) {
                    r.trace.any { it is MessageSent && it.readRound() } shouldBe true
                }
                r.finalState.none { hasLoggedGet(it.log) } shouldBe true
            }
        }
    }
}
