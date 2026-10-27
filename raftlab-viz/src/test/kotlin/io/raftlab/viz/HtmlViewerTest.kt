package io.raftlab.viz

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.trace.ClientInvoked
import io.raftlab.trace.ClientReturned
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.ConfigChanged
import io.raftlab.trace.DropReason
import io.raftlab.trace.EntryApplied
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.Marker
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageDuplicated
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.SnapshotTaken
import io.raftlab.trace.TraceCodec
import io.raftlab.trace.TraceEvent
import io.raftlab.trace.VoteCast
import org.junit.jupiter.api.Test

internal class HtmlViewerTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")

    private val everySubtype: List<TraceEvent> =
        listOf(
            NodeStarted(0, 0, n1),
            RoleChanged(1, 150, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
            MessageSent(2, 150, n1, 1, n1, n2, MessageKind.REQUEST_VOTE, 1, "lastLog=0/0"),
            MessageDuplicated(3, 150, null, 1, 2),
            MessageDelivered(4, 160, n2, 1),
            MessageDropped(5, 165, null, 2, DropReason.RANDOM),
            VoteCast(6, 170, n2, 1, n1, true),
            LogAppended(7, 180, n1, 1, 1, EntryKind.NOOP, "noop"),
            LogTruncated(8, 190, n3, 2),
            CommitAdvanced(9, 200, n1, 1),
            EntryApplied(10, 200, n1, 1),
            SnapshotTaken(11, 210, n1, 1, 1),
            SnapshotInstalled(12, 220, n3, 1, 1),
            ConfigChanged(13, 230, n1, listOf(n1, n2, n3)),
            LeadershipTransfer(14, 240, n1, n2),
            ClientInvoked(15, 250, null, "c1", 1, "put x=1"),
            ClientReturned(16, 260, null, "c1", 1, "ok"),
            PartitionChanged(17, 270, null, listOf(listOf(n1, n2), listOf(n3))),
            NodeCrashed(18, 280, n3),
            NodeRestarted(19, 290, n3),
            Marker(20, 300, null, "end"),
        )

    private fun embedded(html: String): String =
        html
            .substringAfter("<script type=\"application/x-ndjson\" id=\"trace-data\">\n")
            .substringBefore("</script>")

    private fun count(
        html: String,
        needle: String,
    ): Int = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(html).count()

    @Test
    fun selfContainedSingleDocument() {
        val html = HtmlViewer.render(everySubtype)
        everySubtype.map { it::class }.toSet().size shouldBe 21
        html.startsWith("<!DOCTYPE html>\n") shouldBe true
        html.endsWith("</html>\n") shouldBe true
        count(html, "<html") shouldBe 1
        count(html, "</html>") shouldBe 1
        listOf(
            "http://",
            "https://",
            "//",
            "src=",
            "href=",
            "@import",
            "<link",
            "url(",
            "<img",
            "<iframe",
        ).forEach { count(html, it) shouldBe 0 }
        count(html, "<script") shouldBe 2
    }

    @Test
    fun embeddedTraceRoundTrips() {
        TraceCodec.decode(embedded(HtmlViewer.render(everySubtype))) shouldBe everySubtype
    }

    @Test
    fun hostilePayloadStaysInert() {
        val hostile =
            everySubtype +
                Marker(21, 310, NodeId("n<1>"), "</script><!-- \"q\" 'a' & <b>x</b> </SCRIPT >") +
                ClientInvoked(22, 320, null, "c&amp;", 2, "-->& <script>alert(1)</script>")
        val html = HtmlViewer.render(hostile)
        TraceCodec.decode(embedded(html)) shouldBe hostile
        count(html, "</script") shouldBe 2
        count(html, "<script") shouldBe 2
        count(html, "<!--") shouldBe 0
        embedded(html).any { it == '<' || it == '>' || it == '&' } shouldBe false
        count(html, "innerHTML") shouldBe 0
    }

    @Test
    fun controlsAndPositionPresent() {
        val html = HtmlViewer.render(everySubtype)
        listOf(
            "<button id=\"prev\" type=\"button\">Prev</button>",
            "<button id=\"next\" type=\"button\">Next</button>",
            "<button id=\"play\" type=\"button\">Play</button>",
            "'Pause'",
            "<span id=\"position\">",
            "<p id=\"empty\" hidden>no events</p>",
            "PLAY_INTERVAL_MS = 500",
        ).forEach { html shouldContain it }
    }

    @Test
    fun emptyTraceRendersNoEventsPage() {
        val html = HtmlViewer.render(emptyList())
        embedded(html) shouldBe ""
        TraceCodec.decode(embedded(html)) shouldBe emptyList()
        html.contains("no events") shouldBe true
        count(html, "<html") shouldBe 1
    }

    @Test
    fun deterministic() {
        HtmlViewer.render(everySubtype) shouldBe HtmlViewer.render(everySubtype.toList())
    }

    @Test
    fun goldenTinyTrace() {
        val tiny =
            listOf(
                NodeStarted(0, 0, n1),
                Marker(1, 10, null, "a<b & c>d"),
                RoleChanged(2, 150, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
            )
        val expected =
            checkNotNull(HtmlViewerTest::class.java.getResourceAsStream("html-viewer-golden.html"))
                .use { String(it.readAllBytes(), Charsets.UTF_8) }
        HtmlViewer.render(tiny) shouldBe expected
    }

    @Test
    fun embedEscapesOnlySensitiveCharacters() {
        HtmlViewer.embed("a<b>&c\"'/") shouldBe "a\\u003cb\\u003e\\u0026c\"'/"
    }
}
