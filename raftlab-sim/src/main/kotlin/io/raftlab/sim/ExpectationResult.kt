package io.raftlab.sim

/**
 * Outcome of one scenario expectation.
 *
 * @property name Name given to the expectation in the scenario.
 * @property passed Whether the expectation held.
 * @property message Exception text when the check threw, otherwise null.
 */
public data class ExpectationResult(
    /** Name given to the expectation in the scenario. */
    public val name: String,
    /** Whether the expectation held. */
    public val passed: Boolean,
    /** Exception text when the check threw, otherwise null. */
    public val message: String?,
)
