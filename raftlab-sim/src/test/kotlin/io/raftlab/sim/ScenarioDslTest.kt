package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.core.InMemoryStorage
import io.raftlab.kv.KvOp
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

class ScenarioDslTest {
    @Test
    fun preVoteKnobControlsPreVoteMessages() {
        fun sc(on: Boolean) =
            scenario("pv-$on") {
                nodes(3)
                preVote(on)
                awaitLeader(1_000)
                runFor(500)
            }
        val on = sc(true).run(1)
        on.trace.filterIsInstance<MessageSent>().any { it.summary.contains("preVote=true") } shouldBe true
        val off = sc(false).run(1)
        off.trace.filterIsInstance<MessageSent>().any { it.summary.contains("preVote=true") } shouldBe false
        off.trace.filterIsInstance<RoleChanged>().any { it.to == Role.LEADER } shouldBe true
        off.trace.filterIsInstance<RoleChanged>().any { it.to == Role.PRE_CANDIDATE } shouldBe false
        val offSecond = sc(false).run(2)
        offSecond.trace.filterIsInstance<RoleChanged>().any { it.to == Role.PRE_CANDIDATE } shouldBe false
    }

    @Test
    fun builderFunctions() {
        val nodes =
            scenario("nodes3") {
                nodes(3)
                runFor(10)
            }.run(1)
        nodes.finalState.size shouldBe 3

        var created = 0
        val storage =
            scenario("storage") {
                nodes(3)
                storage(
                    StorageKind.Custom {
                        created++
                        InMemoryStorage()
                    },
                )
                runFor(10)
            }
        created shouldBe 0
        storage.run(1)
        created shouldBe 3

        val faults =
            scenario("faults") {
                nodes(3)
                faults(NetworkFaults(7, 7))
                awaitLeader(1_000)
                runFor(500)
            }.run(1)
        val sent = faults.trace.filterIsInstance<MessageSent>().associateBy { it.msgId }
        val delivered = faults.trace.filterIsInstance<MessageDelivered>()
        (delivered.isNotEmpty()) shouldBe true
        for (d in delivered) {
            d.time - checkNotNull(sent[d.msgId]).time shouldBe 7L
        }

        val clients =
            scenario("cl") {
                nodes(3)
                clients(2, 3, listOf("a"))
                awaitLeader(1_000)
                runFor(2_000)
            }.run(1)
        clients.history.entries.size shouldBe 6
    }

    @Test
    fun sameTimeActionsRunInDeclarationOrder() {
        val seen = ArrayList<String>()
        scenario("order") {
            nodes(3)
            at(100) { seen += "a@$now" }
            every(50, 100, 200) { seen += "e@$now" }
            at(100) { seen += "b@$now" }
            at(150) { seen += "c@$now" }
            runFor(300)
        }.run(1)
        seen shouldBe listOf("a@100", "e@100", "b@100", "e@150", "c@150", "e@200")
    }

    @Test
    fun actionsAndViews() {
        var before: NodeId? = null
        var group: Set<NodeId> = emptySet()
        var sid = ""
        var crashed: NodeId? = null
        var s1Up = true
        var s2Up = false
        val r =
            scenario("actions") {
                nodes(5)
                awaitLeader(2_000)
                at(2_000) { crash(n5) }
                at(2_050) { s1Up = node(n5).up }
                at(2_100) { restart(n5) }
                at(2_150) { s2Up = node(n5).up }
                at(2_200) { partition(setOf(n1), setOf(n2, n3, n4, n5)) }
                at(2_300) { heal() }
                at(3_000) {
                    before = leader()?.id
                    group = isolateLeaderWithMinority(2)
                }
                at(3_100) {
                    heal()
                    sid = submitTo(n1, KvOp.Put("k", "v"))
                }
                at(4_000) { crashed = crashLeader() }
                runFor(5_000)
                expect("views") { v -> v.node(n1).log.isNotEmpty() && v.nodes().size == 5 && v.leader() != null }
            }.run(1)

        r.error shouldBe null
        val leaderAtPartition = checkNotNull(before)
        group.size shouldBe 2
        group.first() shouldBe leaderAtPartition
        val order = listOf(n1, n2, n3, n4, n5)
        order[(order.indexOf(leaderAtPartition) - 1 + order.size) % order.size] shouldBe group.toList()[1]
        (r.trace.filterIsInstance<PartitionChanged>().size >= 4) shouldBe true
        sid shouldBe "s1"
        (r.history.ofClient("s1").isNotEmpty()) shouldBe true
        r.finalState.single { it.id == checkNotNull(crashed) }.up shouldBe false
        r.expectations.single { it.name == "views" }.passed shouldBe true
        s1Up shouldBe false
        s2Up shouldBe true
    }

    @Test
    fun crashLeaderWithoutLeaderFails() {
        val r =
            scenario("no-leader") {
                nodes(3)
                at(0) { crashLeader() }
                runFor(10)
            }.run(1)
        val error = checkNotNull(r.error)
        error.contains("crashLeader at t=0") shouldBe true
        error.contains("no leader") shouldBe true
        r.passed shouldBe false
    }

    @Test
    fun awaitLeaderTimeoutFails() {
        val r =
            scenario("timeout") {
                nodes(3)
                faults(NetworkFaults(dropRate = 1000))
                awaitLeader(500)
            }.run(1)
        val error = checkNotNull(r.error)
        error.contains("awaitLeader(within=500)") shouldBe true
        error.contains("no leader by t=500") shouldBe true
    }

    @Test
    fun invalidScriptsRejected() {
        rejects("nodes") { scenario("bad") { nodes(0) } }
        rejects("until") { scenario("bad") { every(10, 100, 50) {} } }
        rejects("count") { scenario("bad") { clients(0, 1, listOf("a")) } }
        rejects("duration") { scenario("bad") { runFor(0) } }
        rejects("no awaitLeader/runFor step") { scenario("bad") { nodes(3) } }
        rejects("duplicate") {
            scenario("bad") {
                awaitLeader(1)
                expect("x") { true }
                expect("x") { true }
            }
        }
        rejects("blank") { scenario(" ") {} }
    }

    @Test
    fun runTimeErrorsNameTheAction() {
        val crashUnknown =
            scenario("x") {
                nodes(3)
                at(10) { crash(NodeId("n9")) }
                runFor(50)
            }.run(1)
        checkNotNull(crashUnknown.error).contains("crash(n9) at t=10") shouldBe true

        val badIsolation =
            scenario("x") {
                nodes(5)
                at(1_500) { isolateLeaderWithMinority(3) }
                awaitLeader(2_000)
                runFor(2_000)
            }.run(1)
        checkNotNull(badIsolation.error).contains("isolateLeaderWithMinority(3)") shouldBe true
    }

    @Test
    fun expectationsNamedAndReported() {
        val r =
            scenario("exp") {
                nodes(3)
                runFor(10)
                expect("ok") { true }
                expect("bad") { false }
                expect("boom") { error("boom") }
            }.run(1)
        r.expectations.map { it.name } shouldBe listOf("ok", "bad", "boom")
        r.expectations.map { it.passed } shouldBe listOf(true, false, false)
        r.expectations[2].message shouldBe "boom"
        r.passed shouldBe false
        r.report.contains("expectation failed: bad") shouldBe true
    }

    @Test
    fun forcedViolationFailsWithSeed() {
        val always =
            object : Invariant {
                override val name = "AlwaysFails"

                override fun check(nodes: List<NodeState>): List<String> = listOf("forced")
            }
        val sc =
            scenario("forced") {
                nodes(3)
                runFor(100)
            }
        val r = sc.prepare(5, checker = InvariantChecker(listOf(always))).execute()
        (r.violations.isNotEmpty()) shouldBe true
        r.violations.all { it.seed == 5L } shouldBe true
        r.passed shouldBe false
        r.report.contains("scenario 'forced' seed 5") shouldBe true
    }

    private fun rejects(
        part: String,
        body: () -> Unit,
    ) {
        val e = shouldThrow<IllegalArgumentException> { body() }
        (e.message ?: "").contains(part) shouldBe true
    }
}
