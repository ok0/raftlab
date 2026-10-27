package io.raftlab.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Source of a stop request for a long-running command.
 */
internal fun interface StopSignal {
    /** Waits up to [timeoutMillis] for a stop request; returns true when a stop was requested. */
    fun awaitStop(timeoutMillis: Long): Boolean
}

internal class LatchStopSignal(
    private val latch: CountDownLatch,
) : StopSignal {
    override fun awaitStop(timeoutMillis: Long): Boolean = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
}

/**
 * Installs INT and TERM handlers that release the returned [StopSignal].
 *
 * Uses `sun.misc.Signal`. A signal that the platform does not support is skipped.
 */
internal object ProcessStopSignal {
    fun install(): StopSignal {
        val latch = CountDownLatch(1)
        for (name in listOf("INT", "TERM")) {
            try {
                sun.misc.Signal.handle(sun.misc.Signal(name)) { latch.countDown() }
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }
        return LatchStopSignal(latch)
    }
}
