package io.raftlab.storage

import io.kotest.matchers.shouldBe
import io.raftlab.core.EntryPayload
import io.raftlab.core.LogEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class TornLogTest {
    @TempDir
    lateinit var tmp: Path

    private fun e(
        i: Long,
        t: Long,
    ): LogEntry = LogEntry(i, t, EntryPayload.Command(byteArrayOf(i.toByte())))

    private fun threeEntries(dir: Path): Pair<Long, Long> {
        val storage = FileStorage(dir)
        storage.append(listOf(e(1, 1), e(2, 1)))
        val afterTwo = Files.size(dir.resolve(FileStorage.LOG_FILE))
        storage.append(listOf(e(3, 2)))
        return afterTwo to Files.size(dir.resolve(FileStorage.LOG_FILE))
    }

    private fun flipLastByte(file: Path) {
        val bytes = Files.readAllBytes(file)
        val last = bytes.size - 1
        bytes[last] = (bytes[last].toInt() xor 0x01).toByte()
        Files.write(file, bytes)
    }

    private fun assertTailDiscarded(
        dir: Path,
        afterTwo: Long,
    ) {
        val log = dir.resolve(FileStorage.LOG_FILE)
        val reopened = FileStorage(dir)
        reopened.lastIndex() shouldBe 2L
        reopened.entries(1, 3) shouldBe listOf(e(1, 1), e(2, 1))
        Files.size(log) shouldBe afterTwo

        reopened.append(listOf(e(3, 5)))
        val again = FileStorage(dir)
        again.entries(1, 4) shouldBe listOf(e(1, 1), e(2, 1), e(3, 5))
    }

    @Test
    fun truncatedMidRecordKeepsPrefix() {
        val dir = tmp.resolve("t")
        val (afterTwo, full) = threeEntries(dir)
        val log = dir.resolve(FileStorage.LOG_FILE)
        FileChannel.open(log, StandardOpenOption.WRITE).use { it.truncate(full - 3) }
        assertTailDiscarded(dir, afterTwo)
    }

    @Test
    fun corruptedChecksumKeepsPrefix() {
        val dir = tmp.resolve("t")
        val (afterTwo, _) = threeEntries(dir)
        flipLastByte(dir.resolve(FileStorage.LOG_FILE))
        assertTailDiscarded(dir, afterTwo)
    }

    @Test
    fun oversizedLengthPrefixKeepsPrefix() {
        val dir = tmp.resolve("t")
        val (afterTwo, _) = threeEntries(dir)
        val log = dir.resolve(FileStorage.LOG_FILE)
        FileChannel.open(log, StandardOpenOption.WRITE).use {
            it.write(ByteBuffer.allocate(4).putInt(0, Int.MAX_VALUE), afterTwo)
        }
        assertTailDiscarded(dir, afterTwo)
    }

    @Test
    fun shortTrailingGarbageIsDiscarded() {
        val dir = tmp.resolve("t")
        val (_, full) = threeEntries(dir)
        val log = dir.resolve(FileStorage.LOG_FILE)
        Files.write(log, byteArrayOf(0, 0, 0), StandardOpenOption.APPEND)

        val reopened = FileStorage(dir)
        reopened.lastIndex() shouldBe 3L
        reopened.entries(1, 4) shouldBe listOf(e(1, 1), e(2, 1), e(3, 2))
        Files.size(log) shouldBe full
    }

    @Test
    fun corruptRecordFollowedByValidDataEndsLog() {
        val dir = tmp.resolve("t")
        val log = dir.resolve(FileStorage.LOG_FILE)
        val first = FileStorage(dir)
        first.append(listOf(e(1, 1)))
        val s1 = Files.size(log)
        first.append(listOf(e(2, 1), e(3, 1), e(4, 1)))

        val bytes = Files.readAllBytes(log)
        val target = (s1 + 9).toInt()
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()
        Files.write(log, bytes)

        val reopened = FileStorage(dir)
        reopened.lastIndex() shouldBe 1L

        reopened.append(listOf(e(2, 9)))
        val again = FileStorage(dir)
        again.entries(1, 3) shouldBe listOf(e(1, 1), e(2, 9))
    }

    @Test
    fun undamagedLogLosesNothing() {
        val dir = tmp.resolve("t")
        val (_, full) = threeEntries(dir)
        val log = dir.resolve(FileStorage.LOG_FILE)

        FileStorage(dir).lastIndex() shouldBe 3L
        val second = FileStorage(dir)
        second.lastIndex() shouldBe 3L
        Files.size(log) shouldBe full
    }
}
