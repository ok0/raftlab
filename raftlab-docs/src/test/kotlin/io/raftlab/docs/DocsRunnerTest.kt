package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

internal class DocsRunnerTest {
    @TempDir
    lateinit var tmp: Path

    private fun run(args: List<String>): Pair<Int, String> {
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)
        val err = PrintStream(errBytes, true, Charsets.UTF_8)
        val code = DocsRunner.run(args, out, err)
        return code to errBytes.toString(Charsets.UTF_8)
    }

    @Test
    fun unknownArgumentIsUsageError() {
        val (code, err) = run(listOf("--bogus"))
        code shouldBe 2
        err shouldContain "usage: raftlab-docs"
    }

    @Test
    fun missingGraphFailsCheck() {
        val (code, err) =
            run(
                listOf(
                    "--out",
                    tmp.resolve("out").toString(),
                    "--module-graph",
                    tmp.resolve("missing.json").toString(),
                    "--readme",
                    tmp.resolve("README.md").toString(),
                    "--check",
                ),
            )
        code shouldBe 1
        err shouldContain "module graph not found"
    }
}
