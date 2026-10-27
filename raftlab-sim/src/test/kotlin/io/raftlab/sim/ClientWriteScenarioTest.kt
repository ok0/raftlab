package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.ClientInvoked
import io.raftlab.trace.ClientReturned
import org.junit.jupiter.api.Test

class ClientWriteScenarioTest {
    private val clients = 3
    private val ops = 15

    @Test
    fun clientWritesCompleteAndStayConsistent() {
        for (r in LibraryRuns.results("client-write")) {
            withClue(r.report) {
                r.expectations.all { it.passed } shouldBe true
                r.history.entries.size shouldBe clients * ops
                r.history.pending().isEmpty() shouldBe true
                ScenarioChecks.sequentialPerClient(r.history) shouldBe true
                val kvViews = r.finalState.map { it.kvData }
                kvViews.distinct().size shouldBe 1
                r.trace.count { it is ClientInvoked } shouldBe clients * ops
                r.trace.count { it is ClientReturned } shouldBe clients * ops
            }
        }
    }

    @Test
    fun ownPutThenGetReadsOwnWrite() {
        var pairs = 0
        for (r in LibraryRuns.results("client-write")) {
            val byClient = r.history.entries.groupBy { it.clientId }
            for (entries in byClient.values) {
                val sorted = entries.sortedBy { it.opId }
                for (i in 0 until sorted.size - 1) {
                    val put = sorted[i]
                    val get = sorted[i + 1]
                    val putOp = put.op
                    val getOp = get.op
                    val consecutive = get.opId == put.opId + 1
                    val putOk = put.result is KvResult.Ok
                    if (putOp is KvOp.Put && putOk && consecutive && getOp is KvOp.Get && getOp.key == putOp.key) {
                        pairs++
                        withClue(r.report) { get.result shouldBe KvResult.Ok(putOp.value) }
                    }
                }
            }
        }
        (pairs >= 1) shouldBe true
    }
}
