package io.raftlab.sim

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.raftlab.core.EntryPayload
import io.raftlab.core.LogEntry
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

internal fun e(
    index: Long,
    term: Long,
    text: String,
): LogEntry = LogEntry(index, term, EntryPayload.Command(text.encodeToByteArray()))

internal fun st(
    id: NodeId,
    log: List<LogEntry>,
    commit: Long = 0L,
    applied: Long = 0L,
    role: Role = Role.FOLLOWER,
    term: Long = 1L,
    up: Boolean = true,
    incarnation: Int = 0,
): NodeState =
    NodeState(
        id,
        up,
        if (up) role else null,
        term,
        null,
        null,
        setOf(n1, n2, n3),
        log,
        commit,
        applied,
        incarnation,
    )

class LogInvariantsTest {
    @Test
    fun standardCheckerRunsAllFiveInvariants() {
        InvariantChecker.standard().invariantNames shouldBe
            listOf(
                "election-safety",
                "log-matching",
                "leader-completeness",
                "state-machine-safety",
                "commit-monotonicity",
            )
    }

    @Test
    fun logMatchingDetectsDivergence() {
        val checker = InvariantChecker(listOf(LogMatching()))
        val found =
            checker.check(
                7,
                123,
                listOf(
                    st(n1, listOf(e(1, 1, "x"), e(2, 2, "y"))),
                    st(n2, listOf(e(1, 1, "z"), e(2, 2, "y"))),
                ),
            )
        found.size shouldBe 1
        found[0].invariant shouldBe "log-matching"
        found[0].seed shouldBe 7L
        found[0].time shouldBe 123L
        (found[0].detail.contains("n1")) shouldBe true
        (found[0].detail.contains("n2")) shouldBe true
        (found[0].detail.contains("index 2")) shouldBe true
    }

    @Test
    fun logMatchingAcceptsAgreeingLogs() {
        val same = listOf(st(n1, listOf(e(1, 1, "x"), e(2, 2, "y"))), st(n2, listOf(e(1, 1, "x"), e(2, 2, "y"))))
        LogMatching().check(same).shouldBeEmpty()

        val differentTermAtTwo =
            listOf(st(n1, listOf(e(1, 1, "x"), e(2, 2, "y"))), st(n2, listOf(e(1, 1, "x"), e(2, 3, "w"))))
        LogMatching().check(differentTermAtTwo).shouldBeEmpty()

        val crashedDivergent =
            listOf(
                st(n1, listOf(e(1, 1, "x"), e(2, 2, "y"))),
                st(n2, listOf(e(1, 1, "z"), e(2, 2, "y")), up = false),
            )
        LogMatching().check(crashedDivergent).shouldBeEmpty()
    }

    @Test
    fun leaderCompletenessDetectsMissingCommittedEntry() {
        val inv = LeaderCompleteness()
        inv.check(listOf(st(n1, listOf(e(1, 1, "a")), commit = 1, term = 2))).shouldBeEmpty()
        inv.check(listOf(st(n2, emptyList(), role = Role.LEADER, term = 2))).shouldBeEmpty()
        val found =
            inv.check(
                listOf(
                    st(n1, listOf(e(1, 1, "a")), commit = 1, term = 2, up = false),
                    st(n3, emptyList(), role = Role.LEADER, term = 3),
                ),
            )
        found.size shouldBe 1
        (found[0].contains("n3")) shouldBe true
        (found[0].contains("index 1")) shouldBe true

        val checker = InvariantChecker(listOf(LeaderCompleteness()))
        checker.check(9, 50, listOf(st(n1, listOf(e(1, 1, "a")), commit = 1, term = 2))).shouldBeEmpty()
        checker.check(9, 50, listOf(st(n2, emptyList(), role = Role.LEADER, term = 2))).shouldBeEmpty()
        val violations =
            checker.check(
                9,
                50,
                listOf(
                    st(n1, listOf(e(1, 1, "a")), commit = 1, term = 2, up = false),
                    st(n3, emptyList(), role = Role.LEADER, term = 3),
                ),
            )
        violations.size shouldBe 1
        violations[0].invariant shouldBe "leader-completeness"
        violations[0].seed shouldBe 9L
        violations[0].time shouldBe 50L
    }

    @Test
    fun stateMachineSafetyDetectsDifferentAppliedEntries() {
        val checker = InvariantChecker(listOf(StateMachineSafety()))
        checker.check(3, 10, listOf(st(n1, listOf(e(1, 1, "a")), commit = 1, applied = 1))).shouldBeEmpty()
        val found =
            checker.check(
                3,
                20,
                listOf(
                    st(n1, listOf(e(1, 1, "a")), commit = 1, applied = 1, up = false),
                    st(n2, listOf(e(1, 2, "b")), commit = 1, applied = 1),
                ),
            )
        found.size shouldBe 1
        found[0].invariant shouldBe "state-machine-safety"
        found[0].seed shouldBe 3L
        found[0].time shouldBe 20L
        (found[0].detail.contains("index 1")) shouldBe true
    }

    @Test
    fun commitMonotonicityDetectsDecrease() {
        val log3 = listOf(e(1, 1, "a"), e(2, 1, "b"), e(3, 1, "c"))
        val checker = InvariantChecker(listOf(CommitMonotonicity()))
        checker.check(4, 1, listOf(st(n1, log3, commit = 3, applied = 3))).shouldBeEmpty()

        val decreased = checker.check(4, 2, listOf(st(n1, log3, commit = 2, applied = 2)))
        (decreased.isNotEmpty()) shouldBe true
        decreased.all { it.invariant == "commit-monotonicity" } shouldBe true
        decreased.all { it.seed == 4L && it.time == 2L } shouldBe true
        (decreased.any { it.detail.contains("commitIndex decreased from 3 to 2") }) shouldBe true

        checker.check(4, 3, listOf(st(n1, log3, commit = 0, applied = 0, incarnation = 1))).shouldBeEmpty()

        val ahead = checker.check(4, 4, listOf(st(n1, log3, commit = 1, applied = 2, incarnation = 1)))
        ahead.size shouldBe 1
        (ahead[0].detail.contains("exceeds commitIndex")) shouldBe true
    }
}
