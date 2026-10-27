package io.raftlab.sim

import java.util.TreeMap

object LibraryRuns {
    val seeds = 1L..20L

    private val cache = TreeMap<String, List<ScenarioResult>>()

    fun results(name: String): List<ScenarioResult> =
        cache.getOrPut(name) { seeds.map { checkNotNull(ScenarioLibrary.all[name]).run(it) } }
}
