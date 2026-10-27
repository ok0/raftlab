package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.raftlab.sim.runNormalElection
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

class ElectionDocRunTest {
    @Test
    fun sameLeaderAsTheM4NormalElection() {
        ElectionDocRun.normalElection(DocEntries.SEED).leader shouldBe runNormalElection(DocEntries.SEED).leader
    }

    @Test
    fun windowEndsAfterEveryFollowerAnsweredTheFirstAppendEntries() {
        val run = ElectionDocRun.normalElection(DocEntries.SEED)
        run.window.first shouldBe 0L
        run.window.last shouldBe run.events.maxOf { it.time }
        val deliveredEvents = run.events.filterIsInstance<MessageDelivered>()
        val delivered = deliveredEvents.map { it.msgId }.toSet()
        val allNodes: Set<NodeId> = (1..5).map { NodeId("n$it") }.toSet()
        val followers: Set<NodeId> = allNodes - run.leader
        val sent = run.events.filterIsInstance<MessageSent>()
        val responses = sent.filter { it.kind == MessageKind.APPEND_ENTRIES_RESPONSE }
        val toLeader = responses.filter { it.to == run.leader }
        val answered: Set<NodeId> = toLeader.filter { it.msgId in delivered }.map { it.from }.toSet()
        answered shouldBe followers
    }

    @Test
    fun electionDiagramShowsVotesAndHeartbeatsWithinCap() {
        val text = DocsGenerator.generate().first { it.path == "election.md" }.content
        val arrows = text.lines().filter { Regex("^    P\\d+(->>|-x)P\\d+: ").containsMatchIn(it) }
        (arrows.size <= DocEntries.MAX_MESSAGES) shouldBe true
        text.contains("more messages omitted") shouldBe false
        text.contains(": RequestVote T") shouldBe true
        text.contains(": RequestVoteResponse T") shouldBe true
        text.contains(": AppendEntries T") shouldBe true
    }

    @Test
    fun rolesDiagramHasFiveNodesAndALeader() {
        val text = DocsGenerator.generate().first { it.path == "roles-election.md" }.content
        Regex("^    state \"[^\"]+\" as node\\d+ \\{$", RegexOption.MULTILINE).findAll(text).count() shouldBe 5
        text.contains("state \"Leader\" as node") shouldBe true
    }
}
