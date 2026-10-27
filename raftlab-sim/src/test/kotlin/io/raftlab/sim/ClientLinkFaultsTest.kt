package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.DropReason
import io.raftlab.trace.MessageDropped
import org.junit.jupiter.api.Test

internal class ClientLinkFaultsTest {
    @Test
    fun rejectsInvalidRates() {
        shouldThrow<IllegalArgumentException> { ClientLinkFaults(dropRate = 1000) }
        shouldThrow<IllegalArgumentException> { ClientLinkFaults(dropRate = -1) }
        shouldThrow<IllegalArgumentException> { ClientLinkFaults(duplicateRate = 1001) }
        shouldThrow<IllegalArgumentException> { ClientLinkFaults(maxExtraDelay = -1) }
        ClientLinkFaults().enabled shouldBe false
    }

    @Test
    fun opsUnchangedByLinkFaults() {
        sc(ClientLinkFaults()).prepare(7).clients.map { it.ops } shouldBe
            sc(ClientLinkFaults(300, 300, 50)).prepare(7).clients.map { it.ops }
    }

    @Test
    fun faultyLinksStayLinearizableWithRetries() {
        val run = sc(ClientLinkFaults(300, 300, 40)).prepare(3)
        val r = run.execute()
        r.passed shouldBe true
        (LinearizabilityChecker.check(r) is LinResult.Linearizable) shouldBe true
        run.clients.any { it.attempts.size > it.ops.size } shouldBe true
    }

    @Test
    fun buildRejectsLinkFaultsWithoutClients() {
        shouldThrow<IllegalArgumentException> {
            scenario("x") {
                nodes(3)
                clientFaults(ClientLinkFaults(dropRate = 1))
                runFor(10)
            }
        }
    }

    @Test
    fun calmFromStopsRandomDrops() {
        val r =
            scenario("calm") {
                nodes(3)
                faults(NetworkFaults(dropRate = 500))
                calmFrom(500)
                runFor(2_000)
            }.run(1)
        val random = r.trace.filterIsInstance<MessageDropped>().filter { it.reason == DropReason.RANDOM }
        (random.any { it.time < 500 }) shouldBe true
        random.none { it.time > 500 } shouldBe true
    }

    private fun sc(f: ClientLinkFaults) =
        scenario("lf") {
            nodes(3)
            clients(3, 10, listOf("a", "b"))
            clientFaults(f)
            runFor(2_000)
        }
}
