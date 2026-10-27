package io.raftlab.sim

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.raftlab.core.Snapshot
import io.raftlab.core.SnapshotMeta
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

internal class SnapshotInvariantsTest {
    private fun withSnap(
        state: NodeState,
        index: Long,
        term: Long,
        data: ByteArray,
    ): NodeState = state.copy(snapshot = Snapshot(SnapshotMeta(index, term, setOf(n1, n2, n3)), data))

    @Test
    fun logMatchingComparesOnlyOverlap() {
        val tail = listOf(e(6, 2, "a6"), e(7, 2, "a7"), e(8, 3, "a8"))
        val a = withSnap(st(n1, tail, commit = 8, applied = 8), 5, 2, ByteArray(0))
        val full = listOf(e(1, 1, "x1"), e(2, 1, "x2"), e(3, 1, "x3"), e(4, 2, "x4"), e(5, 2, "x5"))
        val same = st(n2, full + listOf(e(6, 2, "a6"), e(7, 2, "a7"), e(8, 3, "a8")), commit = 8, applied = 8)
        InvariantChecker(listOf(LogMatching())).check(7L, 123L, listOf(a, same)).shouldBeEmpty()

        val differs = st(n2, full + listOf(e(6, 2, "a6"), e(7, 2, "b7"), e(8, 3, "a8")), commit = 8, applied = 8)
        val found = InvariantChecker(listOf(LogMatching())).check(7L, 123L, listOf(a, differs))
        found.size shouldBe 1
        found[0].invariant shouldBe "log-matching"
    }

    @Test
    fun leaderCompletenessAcceptsSnapshotCoveredEntries() {
        val committed = st(n2, (1L..5L).map { e(it, 2, "c$it") }, commit = 5, applied = 5, term = 2)
        val leader = withSnap(st(n1, emptyList(), role = Role.LEADER, term = 3), 5, 2, ByteArray(0))
        LeaderCompleteness().check(listOf(committed, leader)).shouldBeEmpty()

        val behind = withSnap(st(n1, emptyList(), role = Role.LEADER, term = 3), 3, 1, ByteArray(0))
        LeaderCompleteness().check(listOf(committed, behind)).isEmpty() shouldBe false
    }

    @Test
    fun snapshotTermMismatchIsDetected() {
        val applied = st(n1, (1L..5L).map { e(it, if (it < 5L) 1L else 2L, "t$it") }, commit = 5, applied = 5)
        val snapped = withSnap(st(n2, emptyList(), commit = 5, applied = 5), 5, 3, ByteArray(0))

        val forward = InvariantChecker(listOf(StateMachineSafety())).check(7L, 123L, listOf(applied, snapped))
        forward.size shouldBe 1
        forward[0].invariant shouldBe "state-machine-safety"
        forward[0].seed shouldBe 7L
        forward[0].time shouldBe 123L

        val reversed = InvariantChecker(listOf(StateMachineSafety()))
        reversed.check(7L, 123L, listOf(snapped)).shouldBeEmpty()
        val later = reversed.check(7L, 123L, listOf(applied))
        later.size shouldBe 1
        later[0].invariant shouldBe "state-machine-safety"
    }

    @Test
    fun snapshotDataMismatchIsDetected() {
        val b = withSnap(st(n2, emptyList(), commit = 5, applied = 5), 5, 2, byteArrayOf(1))
        val c = withSnap(st(n3, emptyList(), commit = 5, applied = 5), 5, 2, byteArrayOf(2))
        val found = InvariantChecker(listOf(StateMachineSafety())).check(7L, 123L, listOf(b, c))
        found.size shouldBe 1
        found[0].invariant shouldBe "state-machine-safety"

        val same = withSnap(st(n3, emptyList(), commit = 5, applied = 5), 5, 2, byteArrayOf(1))
        InvariantChecker(listOf(StateMachineSafety())).check(7L, 123L, listOf(b, same)).shouldBeEmpty()
    }

    @Test
    fun commitMonotonicityAcceptsInstallAndRestartJumps() {
        val invariant = CommitMonotonicity()
        invariant.check(listOf(st(n1, emptyList(), commit = 3, applied = 3, incarnation = 0))).shouldBeEmpty()
        invariant.check(listOf(st(n1, emptyList(), commit = 10, applied = 10, incarnation = 0))).shouldBeEmpty()
        invariant.check(listOf(st(n1, emptyList(), commit = 10, applied = 10, incarnation = 1))).shouldBeEmpty()
        invariant.check(listOf(st(n1, emptyList(), commit = 3, applied = 3, incarnation = 2))).shouldBeEmpty()
    }

    @Test
    fun standardCheckerHasSixInvariants() {
        InvariantChecker.standard().invariantNames shouldBe
            listOf(
                "election-safety",
                "log-matching",
                "leader-completeness",
                "state-machine-safety",
                "commit-monotonicity",
                "membership-safety",
            )
    }
}
