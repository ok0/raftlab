package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceRecorder
import io.raftlab.trace.VoteCast
import org.junit.jupiter.api.Test

internal class RaftNodePreVoteTest {
    @Test
    fun timeoutMakesPreCandidateWithoutTermOrStorageChange() {
        val storage = RecordingStorage()
        val rec = recorder()
        val pc = node(storage = storage, trace = rec)
        pc.tick(9) shouldBe emptyList<Effect>()
        pc.tick().sent() shouldBe
            listOf(
                RequestVote(1, n1, n2, 0, 0, true),
                RequestVote(1, n1, n3, 0, 0, true),
            )
        pc.role shouldBe Role.PRE_CANDIDATE
        pc.currentTerm shouldBe 0
        storage.saves.isEmpty() shouldBe true
        rec.events shouldBe
            listOf(RoleChanged(0, 0, n1, Role.FOLLOWER, Role.PRE_CANDIDATE, 0, "election timeout"))
    }

    @Test
    fun preVoteRequestCarriesNextTermAndLastLog() {
        val pc = node(storage = storageWith(3, logWithTerms(1, 3)))
        pc.tick(10).sent() shouldBe
            listOf(
                RequestVote(4, n1, n2, 2, 3, true),
                RequestVote(4, n1, n3, 2, 3, true),
            )
        pc.currentTerm shouldBe 3
    }

    @Test
    fun grantLeavesResponderUntouched() {
        val random = FixedRandom()
        val storage = RecordingStorage()
        val rec = recorder()
        val follower = node(storage = storage, random = random, trace = rec)
        follower.tick(5)
        val bounds = random.bounds.size
        follower.receive(prv(1, n2)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(1, n1, n2, true, true)))
        random.bounds.size shouldBe bounds
        follower.currentTerm shouldBe 0
        follower.role shouldBe Role.FOLLOWER
        storage.saves.isEmpty() shouldBe true
        rec.events.none { it is VoteCast } shouldBe true
        follower.tick(4) shouldBe emptyList<Effect>()
        follower.tick().sent() shouldBe
            listOf(
                RequestVote(1, n1, n2, 0, 0, true),
                RequestVote(1, n1, n3, 0, 0, true),
            )
    }

    @Test
    fun preVoteDenialNotAMember() {
        val storage = RecordingStorage()
        val rec = recorder()
        val follower = node(storage = storage, trace = rec)
        follower.receive(prv(1, n4)) shouldBe emptyList<Effect>()
        follower.currentTerm shouldBe 0
        follower.role shouldBe Role.FOLLOWER
        storage.saves.isEmpty() shouldBe true
        rec.events.none { it is VoteCast } shouldBe true
    }

    @Test
    fun preVoteDenialTermNotGreater() {
        val storage = RecordingStorage(storageWithLog(1, 2).also { it.saveState(PersistentState(2, null)) })
        val rec = recorder()
        val follower = node(storage = storage, trace = rec)
        follower.receive(prv(2, n2, lastIndex = 2, lastTerm = 2)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(2, n1, n2, false, true)))
        follower.currentTerm shouldBe 2
        follower.role shouldBe Role.FOLLOWER
        storage.saves.isEmpty() shouldBe true
        rec.events.none { it is VoteCast } shouldBe true
    }

    @Test
    fun preVoteDenialStaleLog() {
        val storage = RecordingStorage(storageWithLog(1, 2).also { it.saveState(PersistentState(2, null)) })
        val rec = recorder()
        val follower = node(storage = storage, trace = rec)
        follower.receive(prv(3, n2, lastIndex = 5, lastTerm = 1)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(2, n1, n2, false, true)))
        follower.currentTerm shouldBe 2
        follower.role shouldBe Role.FOLLOWER
        storage.saves.isEmpty() shouldBe true
        rec.events.none { it is VoteCast } shouldBe true
    }

    @Test
    fun preVoteDenialLeaderHeardWithinWindow() {
        val storage = RecordingStorage()
        val rec = recorder()
        val follower = node(storage = storage, random = FixedRandom(10), trace = rec)
        follower.receive(ae(1, n2))
        follower.tick(9)
        follower.receive(prv(2, n3)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(1, n1, n3, false, true)))
        follower.tick(1)
        follower.receive(prv(2, n3)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(2, n1, n3, true, true)))
        follower.currentTerm shouldBe 1
        rec.events.none { it is VoteCast } shouldBe true
    }

    @Test
    fun preVoteDenialWhenResponderIsLeader() {
        val storage = RecordingStorage()
        val rec = recorder()
        val leader = leaderAtTerm1(storage = storage, trace = rec)
        storage.saves.clear()
        leader.receive(prv(2, n2, lastIndex = 1, lastTerm = 1)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(1, n1, n2, false, true)))
        leader.role shouldBe Role.LEADER
        leader.currentTerm shouldBe 1
        storage.saves.isEmpty() shouldBe true
        rec.events.none { it is VoteCast } shouldBe true
    }

    @Test
    fun skipLogUpToDateMutationAffectsPreVote() {
        val storage = RecordingStorage(storageWithLog(1, 2).also { it.saveState(PersistentState(2, null)) })
        val follower =
            node(
                storage = storage,
                config = RaftConfig(mutations = setOf(Mutation.SKIP_LOG_UP_TO_DATE_CHECK)),
            )
        follower.receive(prv(3, n2, lastIndex = 5, lastTerm = 1)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, true, true)))
    }

    @Test
    fun grantedResponseNeverAdoptsTerm() {
        val pc = node(members = five)
        pc.tick(10)
        pc.receive(prvr(1, n2)) shouldBe emptyList<Effect>()
        pc.currentTerm shouldBe 0
        pc.role shouldBe Role.PRE_CANDIDATE
        pc.receive(prvr(7, n3)) shouldBe emptyList<Effect>()
        pc.currentTerm shouldBe 0
        pc.role shouldBe Role.PRE_CANDIDATE
    }

    @Test
    fun higherTermDenialAdoptsTermAndFollows() {
        val storage = RecordingStorage()
        val rec = recorder()
        val pc = node(members = five, storage = storage, trace = rec)
        pc.tick(10)
        pc.receive(prvr(4, n2, granted = false)) shouldBe emptyList<Effect>()
        pc.role shouldBe Role.FOLLOWER
        pc.currentTerm shouldBe 4
        storage.saves shouldBe listOf(PersistentState(4, null))
        rec.events.last() shouldBe RoleChanged(1, 0, n1, Role.PRE_CANDIDATE, Role.FOLLOWER, 4, "higher term")

        val fresh = node(members = five)
        fresh.tick(10)
        fresh.receive(prvr(0, n2, granted = false)) shouldBe emptyList<Effect>()
        fresh.role shouldBe Role.PRE_CANDIDATE
        fresh.currentTerm shouldBe 0
    }

    @Test
    fun duplicatesCountOnceAndNonPreCandidateIgnores() {
        val pc = node(members = five)
        pc.tick(10)
        pc.receive(prvr(1, n2)) shouldBe emptyList<Effect>()
        pc.receive(prvr(1, n2)) shouldBe emptyList<Effect>()
        pc.role shouldBe Role.PRE_CANDIDATE
        pc.receive(prvr(1, n3))
        pc.role shouldBe Role.CANDIDATE
        pc.currentTerm shouldBe 1

        val follower = node()
        follower.receive(prvr(1, n2)) shouldBe emptyList<Effect>()
        follower.role shouldBe Role.FOLLOWER
        follower.currentTerm shouldBe 0
    }

    @Test
    fun majorityStartsRealElection() {
        val storage = RecordingStorage()
        val rec = recorder()
        val pc = node(storage = storage, trace = rec)
        pc.tick(10)
        pc.receive(prvr(1, n2)).sent() shouldBe
            listOf(
                RequestVote(1, n1, n2, 0, 0, false),
                RequestVote(1, n1, n3, 0, 0, false),
            )
        pc.role shouldBe Role.CANDIDATE
        pc.currentTerm shouldBe 1
        storage.saves shouldBe listOf(PersistentState(1, n1))
        rec.events shouldBe
            listOf(
                RoleChanged(0, 0, n1, Role.FOLLOWER, Role.PRE_CANDIDATE, 0, "election timeout"),
                RoleChanged(1, 0, n1, Role.PRE_CANDIDATE, Role.CANDIDATE, 1, "pre-vote granted"),
            )
        pc.receive(rvr(1, n2))
        pc.role shouldBe Role.LEADER
    }

    @Test
    fun preCandidateStepsDownOnValidAppendEntries() {
        val rec = recorder()
        val pc = node(trace = rec)
        pc.tick(10)
        pc.receive(ae(0, n2)) shouldBe
            listOf(Effect.Send(AppendEntriesResponse(0, n1, n2, true, 0, 0, 0, 0)))
        pc.role shouldBe Role.FOLLOWER
        pc.leaderHint shouldBe n2
        rec.events.last() shouldBe RoleChanged(1, 0, n1, Role.PRE_CANDIDATE, Role.FOLLOWER, 0, "leader discovered")

        val rec2 = recorder()
        val fresh = node(trace = rec2)
        fresh.tick(10)
        fresh.receive(ae(2, n2))
        fresh.role shouldBe Role.FOLLOWER
        fresh.currentTerm shouldBe 2
        rec2.events.last() shouldBe RoleChanged(1, 0, n1, Role.PRE_CANDIDATE, Role.FOLLOWER, 2, "higher term")
    }

    @Test
    fun preCandidateRerunsPreVoteAndNeverRaisesTerm() {
        val storage = RecordingStorage()
        val rec = recorder()
        val pc = node(storage = storage, trace = rec)
        pc.tick(10)
        pc.tick(9) shouldBe emptyList<Effect>()
        pc.tick().sent() shouldBe
            listOf(
                RequestVote(1, n1, n2, 0, 0, true),
                RequestVote(1, n1, n3, 0, 0, true),
            )
        pc.tick(200)
        pc.currentTerm shouldBe 0
        storage.saves.isEmpty() shouldBe true
        rec.events.filterIsInstance<RoleChanged>().size shouldBe 1
    }

    @Test
    fun candidateTimeoutRunsPreVote() {
        val storage = RecordingStorage()
        val pc = node(storage = storage)
        pc.tick(10)
        pc.receive(prvr(1, n2))
        pc.role shouldBe Role.CANDIDATE
        storage.saves.clear()
        pc.tick(10).sent() shouldBe
            listOf(
                RequestVote(2, n1, n2, 0, 0, true),
                RequestVote(2, n1, n3, 0, 0, true),
            )
        pc.role shouldBe Role.PRE_CANDIDATE
        pc.currentTerm shouldBe 1
        storage.saves.isEmpty() shouldBe true
    }

    @Test
    fun singleMemberElectsItselfDirectly() {
        val rec = recorder()
        val solo = node(members = setOf(n1), trace = rec)
        solo.tick(10)
        solo.role shouldBe Role.LEADER
        solo.currentTerm shouldBe 1
        rec.events.filterIsInstance<RoleChanged>() shouldBe
            listOf(
                RoleChanged(0, 0, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
                RoleChanged(1, 0, n1, Role.CANDIDATE, Role.LEADER, 1, "won election"),
            )
    }

    @Test
    fun preVoteOffIgnoresPreVoteMessages() {
        val pc = node(config = RaftConfig(preVote = false))
        pc.receive(prv(5, n2)) shouldBe emptyList<Effect>()
        pc.receive(prvr(5, n2)) shouldBe emptyList<Effect>()
        pc.currentTerm shouldBe 0
    }

    @Test
    fun realRequestVoteIgnoresStickiness() {
        val pc = node()
        pc.receive(ae(1, n2))
        pc.receive(rv(2, n3)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(2, n1, n3, true, false)))
    }

    @Test
    fun staleConfigNodeCannotDisturbLeader() {
        val rec = recorder()
        val cluster = cluster(preVote = true, rec = rec)
        electLeaderAtTermOne(cluster)
        repeat(1000) {
            cluster.round()
            cluster.nodes.getValue(n1).role shouldBe Role.LEADER
            for (id in listOf(n1, n2, n3)) {
                cluster.nodes.getValue(id).currentTerm shouldBe 1
            }
        }
        val changes = rec.events.filterIsInstance<RoleChanged>()
        changes.any { it.node == n4 && it.to == Role.PRE_CANDIDATE } shouldBe true
        changes.none { it.to == Role.CANDIDATE } shouldBe true
    }

    @Test
    fun staleConfigNodeDisturbsLeaderWithoutPreVote() {
        val cluster = cluster(preVote = false, rec = recorder())
        electLeaderAtTermOne(cluster)
        repeat(1000) { cluster.round() }
        val nodes = cluster.nodes.filterKeys { it in three }.values
        nodes.any { it.currentTerm > 1L } shouldBe true
    }

    private fun electLeaderAtTermOne(cluster: MiniCluster) {
        repeat(10) { cluster.round() }
        cluster.nodes.getValue(n1).role shouldBe Role.LEADER
        cluster.nodes.getValue(n1).currentTerm shouldBe 1
    }

    private fun cluster(
        preVote: Boolean,
        rec: TraceRecorder,
    ): MiniCluster {
        val cfg = RaftConfig(preVote = preVote)
        val nodes =
            mapOf(
                n1 to node(id = n1, members = three, config = cfg, random = FixedRandom(0)),
                n2 to node(id = n2, members = three, config = cfg, random = FixedRandom(10)),
                n3 to node(id = n3, members = setOf(n1, n2, n3, n4), config = cfg, random = FixedRandom(10)),
                n4 to
                    node(
                        id = n4,
                        members = setOf(n1, n2, n3, n4),
                        config = cfg,
                        random = FixedRandom(10),
                        trace = rec,
                    ),
            )
        return MiniCluster(nodes) { m -> (m.from == n4 && m.to != n3) || (m.to == n4 && m.from != n3) }
    }
}
