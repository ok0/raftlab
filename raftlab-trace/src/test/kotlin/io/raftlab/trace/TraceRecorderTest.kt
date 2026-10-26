package io.raftlab.trace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TraceRecorderTest {
    @Test
    fun stampsConsecutiveSeqAndClockTime() {
        var now = 100L
        val r = TraceRecorder { now }
        r.emit { s, t -> Marker(s, t, null, "a") }
        now = 250
        r.emit { s, t -> Marker(s, t, null, "b") }
        r.emit { s, t -> Marker(s, t, null, "c") }
        r.events.map { it.seq } shouldBe listOf(0L, 1L, 2L)
        r.events.map { it.time } shouldBe listOf(100L, 250L, 250L)
        r.events.map { (it as Marker).label } shouldBe listOf("a", "b", "c")
    }

    @Test
    fun startsEmpty() {
        TraceRecorder { 0 }.events shouldBe emptyList()
    }

    @Test
    fun rejectsDraftIgnoringStamp() {
        val r = TraceRecorder { 0 }
        shouldThrow<IllegalStateException> {
            r.emit { _, _ -> Marker(99, 0, null, "x") }
        }
    }
}
