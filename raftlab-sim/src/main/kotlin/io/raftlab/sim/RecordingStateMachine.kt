package io.raftlab.sim

import io.raftlab.core.StateMachine

/** State machine that records every applied command in order; one instance per node incarnation. */
public class RecordingStateMachine : StateMachine {
    private val recorded = ArrayList<AppliedCommand>()

    /** Commands applied so far, in application order. */
    public val applied: List<AppliedCommand> get() = recorded.toList()

    /** Records the command and returns an empty result. */
    public override fun apply(
        index: Long,
        command: ByteArray,
    ): ByteArray {
        recorded.add(AppliedCommand(index, command.copyOf()))
        return ByteArray(0)
    }

    /** Returns an empty snapshot, since the record is the only state. */
    public override fun snapshot(): ByteArray = ByteArray(0)

    /** Does nothing, since the record is the only state. */
    public override fun restore(data: ByteArray) {}
}
