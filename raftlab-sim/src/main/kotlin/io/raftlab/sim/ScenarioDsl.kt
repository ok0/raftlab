package io.raftlab.sim

/**
 * DSL marker for the scenario builder and view receivers.
 *
 * It prevents members of an outer scenario receiver from being called implicitly inside a nested scenario block.
 */
@DslMarker
public annotation class ScenarioDsl
