package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.sim.ScenarioLibrary
import io.raftlab.trace.TraceCodec
import io.raftlab.viz.HtmlViewer
import io.raftlab.viz.LogTableExporter
import io.raftlab.viz.MermaidLint
import io.raftlab.viz.SequenceDiagramExporter
import io.raftlab.viz.StateDiagramExporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class ExportCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val trace =
        ScenarioLibrary.all
            .getValue("normal-election")
            .run(1)
            .trace
    private val end: Long = trace.maxOf { it.time }
    private val two =
        trace
            .mapNotNull { it.node }
            .distinct()
            .sortedBy { it.value }
            .take(2)
            .toSet()

    private fun scenarioExport(vararg extra: String): CliRun =
        CliTest.run("export", "--scenario", "normal-election", "--seed", "1", *extra)

    private fun assertOneErrorLine(result: CliRun) {
        val lines = result.stderr.trimEnd().lines()
        lines.size shouldBe 1
        lines.single().startsWith("error:") shouldBe true
    }

    private fun embeddedCount(html: String): Int {
        val marker = "<script type=\"application/x-ndjson\" id=\"trace-data\">\n"
        return TraceCodec.decode(html.substringAfter(marker).substringBefore("</script>")).size
    }

    @Test
    fun sequenceEqualsExporter() {
        val result = scenarioExport("--format", "sequence")
        result.exitCode shouldBe 0
        result.stdout shouldBe SequenceDiagramExporter().export(trace)
        MermaidLint.lint(result.stdout) shouldBe emptyList()
    }

    @Test
    fun stateEqualsExporter() {
        val result = scenarioExport("--format", "state")
        result.exitCode shouldBe 0
        result.stdout shouldBe StateDiagramExporter().export(trace)
        MermaidLint.lint(result.stdout) shouldBe emptyList()
    }

    @Test
    fun logtableAtAndDefault() {
        val atResult = scenarioExport("--format", "logtable", "--at", "500,1000")
        atResult.exitCode shouldBe 0
        atResult.stdout shouldBe LogTableExporter().export(trace, listOf(500L, 1000L))

        val defaultResult = scenarioExport("--format", "logtable")
        defaultResult.exitCode shouldBe 0
        defaultResult.stdout shouldBe LogTableExporter().export(trace, listOf(end))
    }

    @Test
    fun traceFileMatchesScenario() {
        val traceFile = tmp.resolve("leader-crash.jsonl")
        val written = CliTest.run("scenario", "run", "leader-crash", "--seed", "7", "--trace", traceFile.toString())
        written.exitCode shouldBe 0

        for (format in listOf("sequence", "state")) {
            val fromFile = CliTest.run("export", "--trace", traceFile.toString(), "--format", format)
            val fromScenario = CliTest.run("export", "--scenario", "leader-crash", "--seed", "7", "--format", format)
            fromFile.exitCode shouldBe 0
            fromScenario.exitCode shouldBe 0
            fromFile.stdout shouldBe fromScenario.stdout
        }
    }

    @Test
    fun filtersReduceOutput() {
        val nodesArg = two.joinToString(",") { it.value }
        val first = two.first()

        val sequenceNodes = scenarioExport("--format", "sequence", "--nodes", nodesArg)
        sequenceNodes.exitCode shouldBe 0
        sequenceNodes.stdout shouldBe SequenceDiagramExporter().export(trace, nodes = two)
        val unfilteredSequence = SequenceDiagramExporter().export(trace)
        (sequenceNodes.stdout.length < unfilteredSequence.length) shouldBe true

        val sequenceWindow = scenarioExport("--format", "sequence", "--from", "0", "--to", "${end / 2}")
        sequenceWindow.exitCode shouldBe 0
        sequenceWindow.stdout shouldBe SequenceDiagramExporter().export(trace, window = 0L..end / 2)

        val stateNodes = scenarioExport("--format", "state", "--nodes", nodesArg)
        stateNodes.exitCode shouldBe 0
        stateNodes.stdout shouldBe StateDiagramExporter().export(trace, two)

        val stateWindow = scenarioExport("--format", "state", "--from", "0", "--to", "${end / 2}")
        stateWindow.exitCode shouldBe 0
        stateWindow.stdout shouldBe StateDiagramExporter().export(trace.filter { it.time in 0L..end / 2 })

        val logtableNodes = scenarioExport("--format", "logtable", "--nodes", first.value)
        logtableNodes.exitCode shouldBe 0
        logtableNodes.stdout shouldBe
            LogTableExporter().export(
                trace.filter { it.node == null || it.node in setOf(first) },
                listOf(end),
            )

        scenarioExport("--format", "logtable", "--to", "100", "--at", "500").exitCode shouldBe 2
    }

    @Test
    fun outFileWritesAndPrintsNothing() {
        val outFile = tmp.resolve("diagram.mmd")
        val result = scenarioExport("--format", "sequence", "--out", outFile.toString())
        result.exitCode shouldBe 0
        result.stdout shouldBe ""
        Files.readString(outFile) shouldBe SequenceDiagramExporter().export(trace)
    }

    @Test
    fun usageErrors() {
        val formatError = scenarioExport("--format", "svg")
        formatError.exitCode shouldBe 2
        formatError.stderr shouldContain "state"
        formatError.stderr shouldContain "sequence"
        formatError.stderr shouldContain "logtable"
        formatError.stderr shouldContain "html"
        assertOneErrorLine(formatError)

        val traceFile = tmp.resolve("trace.jsonl")
        Files.writeString(traceFile, TraceCodec.encode(trace))
        val bothSources =
            CliTest.run(
                "export",
                "--trace",
                traceFile.toString(),
                "--scenario",
                "normal-election",
                "--format",
                "sequence",
            )
        bothSources.exitCode shouldBe 2
        assertOneErrorLine(bothSources)

        val neither = CliTest.run("export", "--format", "sequence")
        neither.exitCode shouldBe 2
        assertOneErrorLine(neither)

        val atWithoutLogtable = scenarioExport("--format", "sequence", "--at", "1")
        atWithoutLogtable.exitCode shouldBe 2
        assertOneErrorLine(atWithoutLogtable)

        val malformed = tmp.resolve("malformed.jsonl")
        Files.writeString(malformed, "not json\n")
        val malformedResult = CliTest.run("export", "--trace", malformed.toString(), "--format", "sequence")
        malformedResult.exitCode shouldBe 2
        assertOneErrorLine(malformedResult)

        val atWithHtml = scenarioExport("--format", "html", "--at", "1")
        atWithHtml.exitCode shouldBe 2
        assertOneErrorLine(atWithHtml)
    }

    @Test
    fun htmlEqualsViewer() {
        val result = scenarioExport("--format", "html")
        result.exitCode shouldBe 0
        result.stdout shouldBe HtmlViewer.render(trace)
    }

    @Test
    fun htmlTraceFileMatchesScenario() {
        val traceFile = tmp.resolve("trace.jsonl")
        Files.writeString(traceFile, TraceCodec.encode(trace))
        val fromFile = CliTest.run("export", "--trace", traceFile.toString(), "--format", "html")
        fromFile.exitCode shouldBe 0
        fromFile.stdout shouldBe scenarioExport("--format", "html").stdout
    }

    @Test
    fun htmlOutFileWritesAndPrintsNothing() {
        val outFile = tmp.resolve("viewer.html")
        val result = scenarioExport("--format", "html", "--out", outFile.toString())
        result.exitCode shouldBe 0
        result.stdout shouldBe ""
        Files.readString(outFile) shouldBe HtmlViewer.render(trace)
    }

    @Test
    fun htmlFiltersReduceEmbeddedEvents() {
        val first = two.first()
        val nodesResult = scenarioExport("--format", "html", "--nodes", first.value)
        nodesResult.exitCode shouldBe 0
        nodesResult.stdout shouldBe HtmlViewer.render(trace.filter { it.node == null || it.node == first })
        (embeddedCount(nodesResult.stdout) < trace.size) shouldBe true

        val windowResult = scenarioExport("--format", "html", "--from", "0", "--to", "${end / 2}")
        windowResult.exitCode shouldBe 0
        windowResult.stdout shouldBe HtmlViewer.render(trace.filter { it.time in 0L..end / 2 })
        (embeddedCount(windowResult.stdout) < trace.size) shouldBe true
    }
}
