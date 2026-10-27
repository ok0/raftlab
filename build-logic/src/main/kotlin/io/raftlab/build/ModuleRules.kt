package io.raftlab.build

data class ModuleEdge(val from: String, val to: String, val configuration: String)

object ModuleRules {
    val layers: Map<String, Int> = mapOf(
        ":raftlab-cli" to 1,
        ":raftlab-docs" to 2,
        ":raftlab-viz" to 2,
        ":raftlab-sim" to 3,
        ":raftlab-net" to 3,
        ":raftlab-storage-file" to 3,
        ":raftlab-kv" to 4,
        ":raftlab-core" to 5,
        ":raftlab-trace" to 6,
    )

    val sideways: Set<Pair<String, String>> = setOf(
        ":raftlab-net" to ":raftlab-storage-file",
        ":raftlab-docs" to ":raftlab-viz",
    )

    val exclusiveDependencies: Map<String, Set<String>> = mapOf(
        ":raftlab-viz" to setOf(":raftlab-trace"),
    )

    val mainConfigurations: List<String> = listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly")

    fun violations(edges: List<ModuleEdge>): List<String> {
        val out = mutableListOf<String>()
        for (edge in edges) {
            val lf = layers[edge.from]
            val lt = layers[edge.to]
            when {
                lf == null -> out += "Module ${edge.from} is not listed in ModuleRules.layers"
                lt == null -> out += "Module ${edge.to} (dependency of ${edge.from}) is not listed in ModuleRules.layers"
                lt > lf || (edge.from to edge.to) in sideways -> Unit
                else -> out += "Forbidden dependency ${edge.from} (L$lf) -> ${edge.to} (L$lt) in configuration '${edge.configuration}': a module may depend only on strictly lower layers; sideways allow-list: :raftlab-net -> :raftlab-storage-file, :raftlab-docs -> :raftlab-viz"
            }
            val allowed = exclusiveDependencies[edge.from]
            if (allowed != null && edge.to !in allowed) {
                val only = allowed.sorted().joinToString(", ")
                out += "Forbidden dependency ${edge.from} -> ${edge.to} in configuration '${edge.configuration}': " +
                    "${edge.from} may depend only on $only"
            }
        }
        return out.distinct()
    }
}
