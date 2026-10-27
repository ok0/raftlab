package io.raftlab.sim

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.core.InMemoryStorage
import io.raftlab.core.RaftStorage
import io.raftlab.storage.FileStorage
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class RestartAllScenarioTest {
    private val opened = ArrayList<Path>()
    private val instances = ArrayList<RaftStorage>()

    private val recording =
        DurableStorageProvider { dir ->
            FileStorage(dir).also {
                opened.add(dir)
                instances += it
            }
        }

    private fun expectation(
        result: ScenarioResult,
        name: String,
    ): ExpectationResult =
        checkNotNull(result.expectations.firstOrNull { it.name == name }) { "no expectation named $name" }

    @Test
    fun discoversFileStorageProvider() {
        (DurableStorageProvider.discover() is FileStorageProvider) shouldBe true
    }

    @Test
    fun passesTwentySeeds() {
        for (r in LibraryRuns.results(RestartAllScenario.NAME)) {
            withClue(r.report) {
                r.passed shouldBe true
                r.violations shouldBe emptyList()
                r.finalState.size shouldBe 3
                (r.expectations.map { it.name }.contains(RestartAllScenario.REPLAYED)) shouldBe true
            }
        }
    }

    @Test
    fun restartReopensStorageFromDisk() {
        val r = RestartAllScenario.build(recording).run(1)
        withClue(r.report) {
            r.passed shouldBe true
            opened.size shouldBe 6
            opened.distinct().size shouldBe 3
            instances.toSet().size shouldBe 6
            expectation(r, RestartAllScenario.REPLAYED).passed shouldBe true
        }
    }

    @Test
    fun forgetfulStorageFailsReplay() {
        val r = RestartAllScenario.build(DurableStorageProvider { _ -> InMemoryStorage() }).run(1)
        expectation(r, RestartAllScenario.REPLAYED).passed shouldBe false
        r.passed shouldBe false
    }

    @Test
    fun sameSeedSameBytes() {
        val same = ScenarioLibrary.all.getValue(RestartAllScenario.NAME)
        val a = same.run(5)
        val b = same.run(5)
        val c = same.run(6)
        withClue(a.report) {
            TraceCodec.encode(a.trace) shouldBe TraceCodec.encode(b.trace)
            a.history shouldBe b.history
            a.finalState shouldBe b.finalState
            (TraceCodec.encode(a.trace) != TraceCodec.encode(c.trace)) shouldBe true
            val encoded = TraceCodec.encode(a.trace)
            encoded.contains("raftlab-run-") shouldBe false
            encoded.contains(System.getProperty("java.io.tmpdir").trimEnd('/')) shouldBe false
        }
    }

    @Test
    fun tempDirectoriesRemovedAfterRun() {
        RestartAllScenario.build(recording).run(2)
        opened.none { Files.exists(it) } shouldBe true
        Files.exists(opened.first().parent) shouldBe false
    }

    @Test
    fun runsDoNotShareState() {
        val first = ArrayList<Path>()
        val second = ArrayList<Path>()
        val a =
            RestartAllScenario
                .build(DurableStorageProvider { dir -> FileStorage(dir).also { first.add(dir) } })
                .run(3)
        val b =
            RestartAllScenario
                .build(DurableStorageProvider { dir -> FileStorage(dir).also { second.add(dir) } })
                .run(3)
        (first.first().parent != second.first().parent) shouldBe true
        TraceCodec.encode(a.trace) shouldBe TraceCodec.encode(b.trace)
        a.finalState shouldBe b.finalState
    }
}
