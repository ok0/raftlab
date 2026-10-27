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
    fun writeThenCheckExitsZero() {
        val (writeCode, writeOut, _) = run("--out", dir.toString())
        writeCode shouldBe 0
        writeOut shouldContain "Wrote election.md"
        val (checkCode, checkOut, _) = run("--out", dir.toString(), "--check")
        checkCode shouldBe 0
        checkOut shouldContain "up to date"
    }

    @Test
    fun checkAgainstEmptyDirExitsOne() {
        val target = dir.resolve("none")
        val (code, _, err) = run("--out", target.toString(), "--check")
        code shouldBe 1
        err shouldContain "missing: election.md"
        Files.exists(target) shouldBe false
    }
}
