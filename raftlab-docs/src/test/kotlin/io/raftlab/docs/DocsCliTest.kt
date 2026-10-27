package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class DocsCliTest {
    @TempDir
    lateinit var dir: Path

    private fun run(vararg args: String): Triple<Int, String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8)
        val err = PrintStream(errBytes, true, Charsets.UTF_8)
        val code = DocsCli.run(args.toList(), out, err)
        return Triple(code, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    private fun base(out: Path): Array<String> {
        val graph = dir.resolve("graph.json")
        Files.writeString(graph, DocsFixtures.graphJson())
        return arrayOf(
            "--out",
            out.toString(),
            "--module-graph",
            graph.toString(),
            "--readme",
            DocsFixtures.readme(dir).toString(),
        )
    }

    @Test
    fun unknownArgumentIsUsageError() {
        val (code, _, err) = run("--bogus")
        code shouldBe 2
        err shouldContain "unknown argument: --bogus"
        err shouldContain DocsCli.USAGE
    }

    @Test
    fun missingOutIsUsageError() {
        run().first shouldBe 2
        run("--check").first shouldBe 2
        run("--out").first shouldBe 2
    }

    @Test
    fun missingModuleGraphOrReadmeIsUsageError() {
        val readme = DocsFixtures.readme(dir).toString()
        val out = dir.resolve("out").toString()
        val graph = dir.resolve("graph.json").toString()
        Files.writeString(dir.resolve("graph.json"), DocsFixtures.graphJson())
        val (noGraphCode, _, noGraphErr) = run("--out", out, "--readme", readme)
        noGraphCode shouldBe 2
        noGraphErr shouldContain "missing required --module-graph"
        val (noReadmeCode, _, noReadmeErr) = run("--out", out, "--module-graph", graph)
        noReadmeCode shouldBe 2
        noReadmeErr shouldContain "missing required --readme"
        run("--out", out, "--module-graph", graph, "--readme").first shouldBe 2
    }

    @Test
    fun writeThenCheckExitsZero() {
        val args = base(dir.resolve("out"))
        val (writeCode, writeOut, _) = run(*args)
        writeCode shouldBe 0
        writeOut shouldContain "Wrote election.md"
        writeOut shouldContain "Wrote index.md"
        val (checkCode, checkOut, _) = run(*(args + "--check"))
        checkCode shouldBe 0
        checkOut shouldContain "up to date"
    }

    @Test
    fun checkAgainstEmptyDirExitsOne() {
        val target = dir.resolve("none")
        val (code, _, err) = run(*(base(target) + "--check"))
        code shouldBe 1
        err shouldContain "missing: election.md"
        Files.exists(target) shouldBe false
    }

    @Test
    fun missingModuleGraphFileExitsOne() {
        val readme = DocsFixtures.readme(dir).toString()
        val missing = dir.resolve("nope.json").toString()
        val (code, _, err) = run("--out", dir.resolve("out").toString(), "--module-graph", missing, "--readme", readme)
        code shouldBe 1
        err shouldContain "module graph not found"
    }

    @Test
    fun missingReadmeMarkerExitsOne() {
        val readme = DocsFixtures.readme(dir)
        Files.writeString(readme, "# Test\n\n${DocsGenerator.README_BEGIN}\n")
        val graph = dir.resolve("graph.json").toString()
        Files.writeString(dir.resolve("graph.json"), DocsFixtures.graphJson())
        val (code, _, err) =
            run("--out", dir.resolve("out").toString(), "--module-graph", graph, "--readme", readme.toString())
        code shouldBe 1
        err shouldContain DocsGenerator.README_END
    }
}
