package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.trace.MessageKind
import io.raftlab.trace.NodeId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test

class ModelsTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")

    private val samples: List<RaftMessage> =
        listOf(
            RequestVote(1, n1, n2, 0, 0, false),
            RequestVoteResponse(1, n2, n1, true, false),
            AppendEntries(
                2,
                n1,
                n2,
                0,
                0,
                listOf(
                    LogEntry(1, 1, EntryPayload.NoOp),
                    LogEntry(2, 1, EntryPayload.Command(byteArrayOf(1, 2, 3))),
                    LogEntry(3, 2, EntryPayload.Config(setOf(n1, n2))),
                ),
                0,
                1,
            ),
            AppendEntriesResponse(2, n2, n1, true, 3, 0, 0, 1),
            InstallSnapshot(2, n1, n2, 5, 2, setOf(n1, n2), byteArrayOf(4, 5)),
            InstallSnapshotResponse(2, n2, n1, 5),
            TimeoutNow(2, n1, n2),
        )

    @Test
    fun commandEqualsByContent() {
        EntryPayload.Command(byteArrayOf(1, 2)) shouldBe EntryPayload.Command(byteArrayOf(1, 2))
        EntryPayload.Command(byteArrayOf(1, 2)).hashCode() shouldBe EntryPayload.Command(byteArrayOf(1, 2)).hashCode()
        EntryPayload.Command(byteArrayOf(1, 2)) shouldNotBe EntryPayload.Command(byteArrayOf(1, 3))
        val entry = LogEntry(1, 1, EntryPayload.Command(byteArrayOf(1)))
        entry shouldBe LogEntry(1, 1, EntryPayload.Command(byteArrayOf(1)))
    }

    @Test
    fun roundTripsEveryMessage() {
        for (m in samples) {
            val encoded = Json.encodeToString(serializer<RaftMessage>(), m)
            Json.decodeFromString(serializer<RaftMessage>(), encoded) shouldBe m
        }
    }

    @Test
    fun samplesCoverEveryMessage() {
        val d = serializer<RaftMessage>().descriptor.getElementDescriptor(1)
        val declared = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        val sampled =
            samples
                .map {
                    Json
                        .encodeToJsonElement(serializer<RaftMessage>(), it)
                        .jsonObject["type"]!!
                        .jsonPrimitive.content
                }.toSet()
        sampled shouldBe declared
        declared.size shouldBe MessageKind.entries.size
    }
}
