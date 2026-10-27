package io.raftlab.sim

/**
 * Declares a scenario named [name] whose [body] configures the cluster, clients, timed actions, steps and
 * expectations.
 *
 * The body is validated at once; an invalid script throws `IllegalArgumentException`.
 *
 * @param name Name of the scenario; must not be blank.
 * @param body Declarations of the scenario, evaluated again for every run.
 * @return The declared scenario.
 */
public fun scenario(
    name: String,
    body: ScenarioBuilder.() -> Unit,
): Scenario {
    require(name.isNotBlank()) { "scenario name must not be blank" }
    return Scenario(name, body)
}
