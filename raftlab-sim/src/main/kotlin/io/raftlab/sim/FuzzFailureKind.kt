package io.raftlab.sim

/**
 * Classification of why a fuzz seed failed, in the order [FuzzRunner.evaluate] checks them.
 */
public enum class FuzzFailureKind {
    /** An invariant was violated during the run. */
    INVARIANT,

    /** The client history of the run is not linearizable. */
    LINEARIZABILITY,

    /** The linearizability search exhausted its budget before reaching a verdict. */
    UNKNOWN,

    /** The run stopped with an error before it completed. */
    RUN_ERROR,

    /** One or more scenario expectations failed. */
    EXPECTATION,
}
