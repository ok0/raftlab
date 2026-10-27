package io.raftlab.net

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal object NetThreads {
    fun factory(
        prefix: String,
        daemon: Boolean,
    ): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = daemon }
        }
    }
}
