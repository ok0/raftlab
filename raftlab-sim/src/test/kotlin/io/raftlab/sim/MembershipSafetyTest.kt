package io.raftlab.sim

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.raftlab.core.EntryPayload
import io.raftlab.core.LogEntry
import io.raftlab.core.Snapshot
import io.raftlab.core.SnapshotMeta
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class MembershipSafetyTest {
    private fun st(
        id: NodeId,
        log: List<LogEntry>,
        commit: Long,
        role: Role = Role.FOLLOWER,
        term: Long = 1L,
    ): NodeState = NodeState(id, true, role, term, null, null, setOf(n1, n2, n3), log, commit, commit)

    private fun cfg(
        index: Long,
        vararg ids: String,
    ): LogEntry = LogEntry(index, 1, EntryPayload.Config(ids.map { NodeId(it) }.toSet()))

    private fun noop(index: Long): LogEntry = LogEntry(index, 1, EntryPayload.NoOp)

    private fun initial(): List<NodeState> {
        val empty = emptyList<LogEntry>()
        return listOf(st(n1, empty, 0), st(n2, empty, 0), st(n3, empty, 0))
    }

    @Test
    fun twoServerJumpReported() {
        val checker = InvariantChecker(listOf(MembershipSafety()))
        checker.check(7, 0, initial()).shouldBeEmpty()
        val nodes =
            listOf(
                st(n1, listOf(noop(1), cfg(2, "n1")), 2),
                st(n2, emptyList(), 0),
                st(n3, emptyList(), 0),
            )
        val found = checker.check(7, 5, nodes)
        found.size shouldBe 1
        found[0].invariant shouldBe "membership-safety"
        found[0].seed shouldBe 7
        found[0].time shouldBe 5
        (found[0].detail.contains("changed by 2 servers")) shouldBe true
        checker.check(7, 5, nodes).shouldBeEmpty()
    }

    @Test
    fun twoUncommittedConfigsOnLeaderReported() {
        val checker = InvariantChecker(listOf(MembershipSafety()))
        checker.check(7, 0, initial()).shouldBeEmpty()
        val log = listOf(noop(1), cfg(2, "n1", "n2", "n3", "n4"), cfg(3, "n1", "n2", "n3", "n4", "n5"))
        val nodes =
            listOf(
                st(n1, log, 1, role = Role.LEADER),
                st(n2, emptyList(), 0),
                st(n3, emptyList(), 0),
            )
        val found = checker.check(7, 1, nodes)
        found.size shouldBe 1
        (found[0].detail.contains("uncommitted config entries")) shouldBe true
    }

    @Test
    fun divergingCommittedConfigsReported() {
        val checker = InvariantChecker(listOf(MembershipSafety()))
        checker.check(7, 0, initial()).shouldBeEmpty()
        val nodes =
            listOf(
                st(n1, listOf(noop(1), cfg(2, "n1", "n2", "n3", "n4")), 2),
                st(n2, listOf(noop(1), cfg(2, "n1", "n2", "n3", "n5")), 2),
                st(n3, emptyList(), 0),
            )
        val found = checker.check(7, 1, nodes)
        found.size shouldBe 1
        (found[0].detail.contains("diverge")) shouldBe true
    }

    @Test
    fun singleStepSequenceIsClean() {
        val checker = InvariantChecker(listOf(MembershipSafety()))
        checker.check(7, 0, initial()).shouldBeEmpty()

        val added = listOf(noop(1), cfg(2, "n1", "n2", "n3", "n4"))
        val afterAdd = listOf(st(n1, added, 2), st(n2, added, 2), st(n3, added, 2))
        checker.check(7, 1, afterAdd).shouldBeEmpty()

        val removed = listOf(noop(1), cfg(2, "n1", "n2", "n3", "n4"), cfg(3, "n2", "n3", "n4"))
        val snapshotOnly =
            NodeState(
                n5,
                false,
                null,
                1,
                null,
                null,
                setOf(n2, n3, n4),
                emptyList(),
                0,
                0,
                0,
                Snapshot(SnapshotMeta(3, 1, setOf(n2, n3, n4)), ByteArray(0)),
            )
        val afterRemove =
            listOf(
                st(n1, removed, 3),
                st(n2, removed, 3),
                st(n3, removed, 3),
                st(n4, removed, 3),
                snapshotOnly,
            )
        checker.check(7, 2, afterRemove).shouldBeEmpty()
        checker.check(7, 3, afterRemove).shouldBeEmpty()
    }
}
