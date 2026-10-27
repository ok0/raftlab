package io.raftlab.sim

import java.util.PriorityQueue

/** An event popped from an [EventQueue] together with its scheduled time. */
internal data class Scheduled<T>(
    val time: Long,
    val payload: T,
)

/** Deterministic time-ordered queue; events with equal times pop in insertion order. */
internal class EventQueue<T> {
    private class Entry<T>(
        val time: Long,
        val seq: Long,
        val payload: T,
    )

    private val heap =
        PriorityQueue<Entry<T>>(
            Comparator { a, b -> if (a.time != b.time) a.time.compareTo(b.time) else a.seq.compareTo(b.seq) },
        )
    private var nextSeq = 0L

    val size: Int get() = heap.size

    fun push(
        time: Long,
        payload: T,
    ) {
        heap.add(Entry(time, nextSeq++, payload))
    }

    fun peekTime(): Long? = heap.peek()?.time

    fun pop(): Scheduled<T> {
        check(heap.isNotEmpty()) { "event queue is empty" }
        val e = heap.poll()
        return Scheduled(e.time, e.payload)
    }
}
