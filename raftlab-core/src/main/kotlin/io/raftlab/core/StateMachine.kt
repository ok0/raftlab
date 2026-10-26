package io.raftlab.core

/**
 * Deterministic application state driven by committed log entries.
 */
public interface StateMachine {
    /**
     * Applies [command] committed at log position [index] and returns the result bytes.
     * Must be deterministic for the same sequence of commands.
     */
    public fun apply(
        index: Long,
        command: ByteArray,
    ): ByteArray

    /** Returns an opaque snapshot of the current state, usable by [restore]. */
    public fun snapshot(): ByteArray

    /** Replaces the current state with one previously produced by [snapshot]. */
    public fun restore(data: ByteArray)
}
