package io.raftlab.trace

/** An event before its sequence number and time are assigned. */
public fun interface TraceEventDraft {
    /** Completes this draft into a [TraceEvent] with the given [seq] and [time]. */
    public fun stamp(
        seq: Long,
        time: Long,
    ): TraceEvent
}

/** Receives trace events emitted during a run. */
public fun interface TraceSink {
    /** Stamps [event] and records it. */
    public fun emit(event: TraceEventDraft)
}

/** Records stamped events in emission order, reading time from [clock]. */
public class TraceRecorder(
    private val clock: () -> Long,
) : TraceSink {
    private var nextSeq = 0L
    private val recorded = ArrayList<TraceEvent>()

    /** Snapshot of the events recorded so far. */
    public val events: List<TraceEvent> get() = recorded.toList()

    /** Stamps [event] with the next sequence number and the current clock value, then records it. */
    override fun emit(event: TraceEventDraft) {
        val seq = nextSeq
        val time = clock()
        val e = event.stamp(seq, time)
        check(e.seq == seq && e.time == time) { "draft ignored stamp seq=$seq time=$time" }
        recorded.add(e)
        nextSeq = seq + 1
    }
}
