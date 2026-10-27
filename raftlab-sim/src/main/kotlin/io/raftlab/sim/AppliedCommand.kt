package io.raftlab.sim

/** One command applied to a [RecordingStateMachine]. */
public class AppliedCommand(
    /** Log index at which the command was applied. */
    public val index: Long,
    /** Command bytes as applied. */
    public val bytes: ByteArray,
) {
    /** Two applied commands are equal when their index and bytes are equal. */
    public override fun equals(other: Any?): Boolean =
        other is AppliedCommand && index == other.index && bytes.contentEquals(other.bytes)

    /** Hash code consistent with [equals]. */
    public override fun hashCode(): Int = 31 * index.hashCode() + bytes.contentHashCode()

    /** Readable form including the index and the command bytes. */
    public override fun toString(): String = "AppliedCommand(index=$index, bytes=${bytes.contentToString()})"
}
