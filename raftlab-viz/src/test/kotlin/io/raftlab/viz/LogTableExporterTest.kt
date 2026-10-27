package io.raftlab.viz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.TraceEvent
import org.junit.jupiter.api.Test

internal class LogTableExporterTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")

    private val baseTrace: List<TraceEvent> =
        listOf(
            NodeStarted(0, 0, n1),
            NodeStarted(1, 0, n2),
            NodeStarted(2, 0, n3),
            LogAppended(3, 10, n1, 1, 1, EntryKind.NOOP, ""),
            LogAppended(4, 11, n2, 1, 1, EntryKind.NOOP, ""),
            LogAppended(5, 11, n3, 1, 1, EntryKind.NOOP, ""),
            CommitAdvanced(6, 12, n1, 1),
            LogAppended(7, 20, n1, 2, 1, EntryKind.NOOP, ""),
            LogAppended(8, 30, n2, 2, 2, EntryKind.NOOP, ""),
            NodeCrashed(9, 40, n3),
        )

    private val extendedTrace: List<TraceEvent> =
        baseTrace +
            listOf(
                LogTruncated(10, 50, n2, 2),
                NodeRestarted(11, 60, n3),
                SnapshotInstalled(12, 70, n3, 2, 1),
            )

    private val exporter = LogTableExporter()

    @Test
    fun goldenCommittedDivergentAndDown() {
        val out = exporter.export(baseTrace, listOf(15, 45))
        out shouldBe
            "**t = 15 ms**\n\n" +
            "| Node | Status | 1 |\n" +
            "| --- | --- | --- |\n" +
            "| n1 | up | **1** |\n" +
            "| n2 | up | 1 |\n" +
            "| n3 | up | 1 |\n" +
            "\n" +
            "**t = 45 ms**\n\n" +
            "| Node | Status | 1 | 2 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| n1 | up | **1** | 1 |\n" +
            "| n2 | up | 1 | 2 |\n" +
            "| n3 | down | 1 |  |\n"
    }

    @Test
    fun columnCapNotesHiddenIndexes() {
        val out = exporter.export(baseTrace, listOf(45), maxColumns = 1)
        out shouldBe
            "**t = 45 ms**\n\n" +
            "Indexes 1-1 not shown.\n\n" +
            "| Node | Status | 2 |\n" +
            "| --- | --- | --- |\n" +
            "| n1 | up | 1 |\n" +
            "| n2 | up | 2 |\n" +
            "| n3 | down |  |\n"
    }

    @Test
    fun truncateRestartAndSnapshot() {
        val out = exporter.export(extendedTrace, listOf(55, 65, 75))
        out shouldContain "| n2 | up | 1 |  |"
        out shouldContain "| n3 | up | 1 |  |"
        out shouldContain "| n3 | up | **S** | **S** |"
    }

    @Test
    fun byteStableAndOrderIndependent() {
        val times = listOf(15L, 45L)
        val first = exporter.export(baseTrace, times)
        exporter.export(baseTrace, times) shouldBe first
        exporter.export(baseTrace.reversed(), times) shouldBe first

        val input = baseTrace.toMutableList()
        exporter.export(input, times)
        input shouldBe baseTrace
    }

    @Test
    fun rejectsBadArguments() {
        shouldThrow<IllegalArgumentException> { exporter.export(baseTrace, emptyList()) }
        shouldThrow<IllegalArgumentException> { exporter.export(baseTrace, listOf(45), maxColumns = 0) }
    }
}
