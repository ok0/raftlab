package io.raftlab.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.core.AppendEntries
import io.raftlab.core.AppendEntriesResponse
import io.raftlab.core.EntryPayload
import io.raftlab.core.InstallSnapshot
import io.raftlab.core.InstallSnapshotResponse
import io.raftlab.core.LogEntry
import io.raftlab.core.RequestVote
import io.raftlab.core.RequestVoteResponse
import io.raftlab.core.TimeoutNow
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

internal class FrameCodecTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")

    private val frames: List<Frame> =
        listOf(
            Frame.Peer(RequestVote(term = 1, from = n1, to = n2, lastLogIndex = 2, lastLogTerm = 1, preVote = true)),
            Frame.Peer(RequestVoteResponse(term = 1, from = n2, to = n1, granted = true, preVote = false)),
            Frame.Peer(
                AppendEntries(
                    term = 1,
                    from = n1,
                    to = n2,
                    prevLogIndex = 0,
                    prevLogTerm = 0,
                    entries =
                        listOf(
                            LogEntry(1, 1, EntryPayload.NoOp),
                            LogEntry(2, 1, EntryPayload.Command(byteArrayOf(1, 2, 3, -1))),
                            LogEntry(3, 1, EntryPayload.Config(setOf(n1, n2))),
                        ),
                    leaderCommit = 2,
                    readSeq = 5,
                ),
            ),
            Frame.Peer(
                AppendEntriesResponse(
                    term = 1,
                    from = n2,
                    to = n1,
                    success = false,
                    matchIndex = 0,
                    conflictIndex = 1,
                    conflictTerm = 1,
                    readSeq = 5,
                ),
            ),
            Frame.Peer(
                InstallSnapshot(
                    term = 2,
                    from = n1,
                    to = n3,
                    lastIndex = 9,
                    lastTerm = 2,
                    members = setOf(n1, n2, n3),
                    data = byteArrayOf(0, 7, -128, 127),
                ),
            ),
            Frame.Peer(InstallSnapshotResponse(term = 2, from = n3, to = n1, lastIndex = 9)),
            Frame.Peer(TimeoutNow(term = 3, from = n1, to = n2)),
            Frame.Request(ClientRequest(7, KvCommand("c1", 3, KvOp.Cas("k", null, "v")))),
            Frame.Request(ClientRequest(8, KvCommand("c1", 4, KvOp.Put("k", "v")))),
            Frame.Response(ClientResponse(7, KvResult.NotLeader(n2))),
            Frame.Response(ClientResponse(8, KvResult.Ok(null))),
            Frame.Response(ClientResponse(9, KvResult.CasFailed("x"))),
            Frame.Response(ClientResponse(10, KvResult.Timeout)),
        )

    @Test
    fun everyFrameTypeRoundTrips() {
        frames.forEach { f ->
            FrameCodec.read(ByteArrayInputStream(FrameCodec.encode(f))) shouldBe f
        }
        val stream = ByteArrayInputStream(frames.fold(ByteArray(0)) { acc, f -> acc + FrameCodec.encode(f) })
        frames.forEach { f ->
            FrameCodec.read(stream) shouldBe f
        }
        FrameCodec.read(stream) shouldBe null
    }

    @Test
    fun lengthPrefixIsBigEndianBodySize() {
        frames.forEach { f ->
            val bytes = FrameCodec.encode(f)
            ByteBuffer.wrap(bytes, 0, 4).int shouldBe bytes.size - 4
        }
    }

    @Test
    fun encodeRejectsFramesOverTheCap() {
        val big =
            Frame.Peer(
                InstallSnapshot(
                    term = 1,
                    from = n1,
                    to = n2,
                    lastIndex = 1,
                    lastTerm = 1,
                    members = setOf(n1),
                    data = ByteArray(FrameCodec.MAX_FRAME_BYTES / 2 + 1),
                ),
            )
        shouldThrow<FrameException> { FrameCodec.encode(big) }
    }

    @Test
    fun readRejectsOversizedLength() {
        val stream = ByteBuffer.allocate(4).putInt(FrameCodec.MAX_FRAME_BYTES + 1).array()
        shouldThrow<FrameException> { FrameCodec.read(ByteArrayInputStream(stream)) }
    }

    @Test
    fun readRejectsNegativeLength() {
        val stream = ByteBuffer.allocate(4).putInt(-1).array()
        shouldThrow<FrameException> { FrameCodec.read(ByteArrayInputStream(stream)) }
    }

    @Test
    fun readRejectsUndecodableBodies() {
        val bodies =
            listOf(
                "hello",
                """{"type":"bogus"}""",
                "{\"type\":\"request\",\"request\":{\"requestId\":1," +
                    "\"command\":{\"clientId\":\"c\",\"seq\":0,\"op\":{\"type\":\"Get\",\"key\":\"k\"}}}}",
            )
        bodies.forEach { body ->
            shouldThrow<FrameException> { FrameCodec.read(ByteArrayInputStream(prefixed(body.encodeToByteArray()))) }
        }
    }

    @Test
    fun readRejectsTruncatedFrames() {
        shouldThrow<FrameException> { FrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0))) }
        val truncated = ByteBuffer.allocate(4).putInt(10).array() + byteArrayOf(1, 2, 3)
        shouldThrow<FrameException> { FrameCodec.read(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun readReturnsNullAtCleanEnd() {
        FrameCodec.read(ByteArrayInputStream(ByteArray(0))) shouldBe null
    }

    private fun prefixed(body: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + body.size)
        buf.putInt(body.size).put(body)
        return buf.array()
    }
}
