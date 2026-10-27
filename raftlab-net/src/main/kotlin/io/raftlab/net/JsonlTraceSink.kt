package io.raftlab.net

import io.raftlab.trace.TraceCodec
import io.raftlab.trace.TraceEventDraft
import io.raftlab.trace.TraceSink
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

internal class JsonlTraceSink(
    path: Path,
    private val clock: () -> Long,
) : TraceSink,
    Closeable {
    init {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    }

    private val writer = Files.newBufferedWriter(path, Charsets.UTF_8, CREATE, TRUNCATE_EXISTING, WRITE)
    private var nextSeq = 0L

    override fun emit(event: TraceEventDraft) {
        val seq = nextSeq
        val time = clock()
        val e = event.stamp(seq, time)
        check(e.seq == seq && e.time == time) { "draft ignored stamp seq=$seq time=$time" }
        writer.write(TraceCodec.encode(listOf(e)))
        writer.flush()
        nextSeq = seq + 1
    }

    override fun close() {
        writer.close()
    }
}
