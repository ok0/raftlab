package io.raftlab.kv

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

class KvClientCoreTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")
    private val n9 = NodeId("n9")

    private fun newCore(): KvClientCore = KvClientCore("a", setOf(NodeId("n3"), NodeId("n1"), NodeId("n2")))

    private fun KvClientCore.reply(result: KvResult): KvClientAction =
        onResponse(ClientResponse(inFlight!!.request.requestId, result))

    @Test
    fun firstTargetIsLowestMember() {
        val core = newCore()
        val send = core.submit(KvOp.Put("k", "v"))
        send.target shouldBe n1
        send.request.command.seq shouldBe 1L
        send.request.requestId shouldBe 1L
    }

    @Test
    fun notLeaderWithKnownHintRetargets() {
        val core = newCore()
        val first = core.submit(KvOp.Put("k", "v"))
        val action = core.reply(KvResult.NotLeader(n3)) as KvClientAction.Send
        action.target shouldBe n3
        action.request.command shouldBe first.request.command
        action.request.requestId shouldBe 2L
    }

    @Test
    fun notLeaderWithNullOrUnknownHintRotatesAndWraps() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        (core.reply(KvResult.NotLeader(null)) as KvClientAction.Send).target shouldBe n2
        (core.reply(KvResult.NotLeader(n9)) as KvClientAction.Send).target shouldBe n3
        val wrapped = core.reply(KvResult.NotLeader(null)) as KvClientAction.Send
        wrapped.target shouldBe n1
        wrapped.request.command.seq shouldBe 1L
    }

    @Test
    fun timeoutResponseRotates() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        (core.reply(KvResult.Timeout) as KvClientAction.Send).target shouldBe n2
        val retry = core.onTimeout() as KvClientAction.Send
        retry.target shouldBe n3
        retry.request.command.seq shouldBe 1L
    }

    @Test
    fun driverTimeoutRotates() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        val retry = core.onTimeout() as KvClientAction.Send
        retry.target shouldBe n2
        retry.request.command.seq shouldBe 1L
    }

    @Test
    fun seqIncrementsOnlyAfterFinalResult() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        core.reply(KvResult.NotLeader(null))
        core.nextSeq shouldBe 1L
        core.reply(KvResult.Timeout)
        core.nextSeq shouldBe 1L
        core.reply(KvResult.Ok(null)) shouldBe KvClientAction.Done(KvResult.Ok(null))
        core.nextSeq shouldBe 2L
        core.inFlight shouldBe null
        val second = core.submit(KvOp.Get("k"))
        second.request.command.seq shouldBe 2L
        core.reply(KvResult.CasFailed("x")) shouldBe KvClientAction.Done(KvResult.CasFailed("x"))
        core.nextSeq shouldBe 3L
    }

    @Test
    fun successRemembersRespondingServer() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        core.reply(KvResult.NotLeader(n3))
        core.reply(KvResult.Ok(null))
        core.leaderGuess shouldBe n3
        core.submit(KvOp.Get("k")).target shouldBe n3
    }

    @Test
    fun staleResponseIsIgnored() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        core.reply(KvResult.NotLeader(null))
        core.onResponse(ClientResponse(1L, KvResult.Ok(null))) shouldBe KvClientAction.Ignore
        val idle = newCore()
        idle.onResponse(ClientResponse(1L, KvResult.Ok(null))) shouldBe KvClientAction.Ignore
        idle.onTimeout() shouldBe KvClientAction.Ignore
    }

    @Test
    fun secondSubmitWhileInFlightFails() {
        val core = newCore()
        core.submit(KvOp.Put("k", "v"))
        shouldThrow<IllegalStateException> { core.submit(KvOp.Get("k")) }
    }

    @Test
    fun isDeterministic() {
        fun script(core: KvClientCore): List<KvClientAction> {
            val actions = mutableListOf<KvClientAction>()
            actions += core.submit(KvOp.Put("k", "v"))
            actions += core.reply(KvResult.NotLeader(n2))
            actions += core.onTimeout()
            actions += core.reply(KvResult.Timeout)
            actions += core.reply(KvResult.Ok(null))
            actions += core.submit(KvOp.Cas("k", null, "w"))
            actions += core.reply(KvResult.CasFailed("v"))
            return actions
        }
        script(newCore()) shouldBe script(newCore())
    }
}
