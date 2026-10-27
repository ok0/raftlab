package io.raftlab.viz

import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.TraceEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private fun flowText(s: String): String = MermaidText.label(s).ifEmpty { "_" }

/**
 * Builds a Mermaid `flowchart` with optional subgraphs, nodes and labelled edges.
 *
 * Subgraphs get ids `g<i>` and nodes get ids `n<i>`, both by insertion order. Keys are only used by the caller and
 * are never rendered. Every title and label is sanitized with the same rules as the other viz exporters, and a label
 * that becomes empty is rendered as `_`. Subgraphs are rendered first, each followed by its nodes in insertion order,
 * then the nodes outside any subgraph, then the edges in insertion order. The output has `\n` line endings and ends
 * with a newline.
 */
public class FlowchartBuilder(
    private val direction: Direction = Direction.TB,
) {
    /** Orientation of the rendered flowchart. */
    public enum class Direction {
        /** Top to bottom. */
        TB,

        /** Left to right. */
        LR,
    }

    private class Cluster(
        val id: String,
        val title: String,
    )

    private class FlowNode(
        val id: String,
        val label: String,
        val subgraph: String?,
    )

    private class FlowEdge(
        val from: String,
        val to: String,
        val label: String?,
    )

    private val subgraphs = LinkedHashMap<String, Cluster>()
    private val nodes = LinkedHashMap<String, FlowNode>()
    private val edges = mutableListOf<FlowEdge>()

    /**
     * Adds a subgraph under [key] with the given [title].
     *
     * @param key caller-defined key, must be unique among subgraphs
     * @param title title shown for the subgraph
     * @return this builder
     * @throws IllegalArgumentException if [key] is already used by a subgraph
     */
    public fun subgraph(
        key: String,
        title: String,
    ): FlowchartBuilder {
        require(key !in subgraphs) { "duplicate subgraph key: $key" }
        subgraphs[key] = Cluster("g${subgraphs.size}", title)
        return this
    }

    /**
     * Adds a node under [key] with the given [label], optionally placed inside the subgraph [subgraph].
     *
     * @param key caller-defined key, must be unique among nodes
     * @param label text shown for the node
     * @param subgraph key of an existing subgraph to place the node in, or null for no subgraph
     * @return this builder
     * @throws IllegalArgumentException if [key] is already used by a node or [subgraph] is not a known subgraph
     */
    public fun node(
        key: String,
        label: String,
        subgraph: String? = null,
    ): FlowchartBuilder {
        require(key !in nodes) { "duplicate node key: $key" }
        require(subgraph == null || subgraph in subgraphs) { "unknown subgraph: $subgraph" }
        nodes[key] = FlowNode("n${nodes.size}", label, subgraph)
        return this
    }

    /**
     * Adds an edge from the node [from] to the node [to], with an optional [label].
     *
     * Duplicate edges are kept; callers are responsible for deduplication.
     *
     * @param from key of the source node
     * @param to key of the target node
     * @param label text shown on the edge, or null for no label
     * @return this builder
     * @throws IllegalArgumentException if [from] or [to] is not a known node
     */
    public fun edge(
        from: String,
        to: String,
        label: String? = null,
    ): FlowchartBuilder {
        require(from in nodes && to in nodes) { "unknown node in edge: $from -> $to" }
        edges += FlowEdge(nodes.getValue(from).id, nodes.getValue(to).id, label)
        return this
    }

    /**
     * Renders the flowchart as Mermaid text.
     *
     * @return the Mermaid `flowchart` text, with `\n` line endings and a trailing newline
     */
    public fun render(): String {
        val out = StringBuilder()
        out.append("flowchart ").append(direction.name).append('\n')
        for ((key, cluster) in subgraphs) {
            out.append("    subgraph ${cluster.id}[\"${flowText(cluster.title)}\"]\n")
            for (node in nodes.values.filter { it.subgraph == key }) {
                out.append("        ${node.id}[\"${flowText(node.label)}\"]\n")
            }
            out.append("    end\n")
        }
        for (node in nodes.values.filter { it.subgraph == null }) {
            out.append("    ${node.id}[\"${flowText(node.label)}\"]\n")
        }
        for (edge in edges) {
            val label = edge.label?.let { "|${flowText(it)}|" }
            out.append("    ${edge.from} -->")
            if (label != null) out.append(label)
            out.append(" ${edge.to}\n")
        }
        return out.toString()
    }
}

/**
 * Dependency graph of the Gradle modules, read from the JSON written by `writeModuleGraph`.
 *
 * The JSON shape written by `writeModuleGraph` is
 * `{"modules":[{"name":"raftlab-core","layer":5},…],"edges":[{"from":"raftlab-core","to":"raftlab-trace"},…]}`
 * with module names without a leading `:`. Unknown keys are rejected. Every module has a layer of at least 1, module
 * names are unique, and every edge refers to listed modules.
 *
 * @property modules the modules of the build
 * @property edges the dependencies between modules, where `from` depends on `to`
 */
@Serializable
public data class ModuleGraph(
    /** The modules of the build. */
    public val modules: List<Module>,
    /** The dependencies between modules, where `from` depends on `to`. */
    public val edges: List<Edge>,
) {
    init {
        require(modules.map { it.name }.toSet().size == modules.size) { "duplicate module name" }
        require(modules.all { it.layer >= 1 }) { "module layer must be at least 1" }
        val names = modules.map { it.name }.toSet()
        require(edges.all { it.from in names && it.to in names }) { "edge references unknown module" }
    }

    /**
     * A Gradle module and its architectural layer.
     *
     * @property name module name without a leading `:`
     * @property layer layer number, at least 1, where lower layers are more fundamental
     */
    @Serializable
    public data class Module(
        /** Module name without a leading `:`. */
        public val name: String,
        /** Layer number, at least 1, where lower layers are more fundamental. */
        public val layer: Int,
    )

    /**
     * A dependency from one module to another.
     *
     * @property from name of the depending module
     * @property to name of the module it depends on
     */
    @Serializable
    public data class Edge(
        /** Name of the depending module. */
        public val from: String,
        /** Name of the module it depends on. */
        public val to: String,
    )

    /** Parses [ModuleGraph] instances from the JSON format written by `writeModuleGraph`. */
    public companion object {
        /**
         * Parses the module graph from [json].
         *
         * @param json JSON text in the shape documented on [ModuleGraph]
         * @return the parsed module graph
         * @throws IllegalArgumentException if the JSON is malformed, has unknown keys or breaks a [ModuleGraph] check
         */
        public fun fromJson(json: String): ModuleGraph = Json.decodeFromString(serializer(), json)
    }
}

/** Renders a [ModuleGraph] as Mermaid flowcharts. */
public object ModuleGraphRenderer {
    /**
     * Renders every module as a node grouped by layer, with every dependency as an edge.
     *
     * Layers are subgraphs in ascending order, modules inside a layer are sorted by name, and edges are sorted by
     * `(from, to)`.
     *
     * @param graph the module graph to render
     * @return the Mermaid `flowchart TB` text
     */
    public fun dependencies(graph: ModuleGraph): String {
        val builder = FlowchartBuilder(FlowchartBuilder.Direction.TB)
        val layers =
            graph.modules
                .map { it.layer }
                .distinct()
                .sorted()
        for (layer in layers) {
            builder.subgraph("L$layer", "Layer $layer")
        }
        for (layer in layers) {
            for (module in graph.modules.filter { it.layer == layer }.sortedBy { it.name }) {
                builder.node(module.name, module.name, "L$layer")
            }
        }
        for (edge in graph.edges.sortedWith(compareBy<ModuleGraph.Edge>({ it.from }, { it.to }))) {
            builder.edge(edge.from, edge.to)
        }
        return builder.render()
    }

    /**
     * Renders one node per layer listing its modules, with an edge for each distinct pair of layers that depend on
     * each other.
     *
     * Layer nodes are in ascending order, and edges are sorted by `(from layer, to layer)`. Dependencies within a
     * single layer are not shown.
     *
     * @param graph the module graph to render
     * @return the Mermaid `flowchart TB` text
     */
    public fun overview(graph: ModuleGraph): String {
        val builder = FlowchartBuilder(FlowchartBuilder.Direction.TB)
        val byLayer = graph.modules.groupBy { it.layer }.toSortedMap()
        for ((layer, modules) in byLayer) {
            val names = modules.map { it.name }.sorted().joinToString(", ")
            builder.node("L$layer", "Layer $layer ($names)")
        }
        val layerOf = graph.modules.associate { it.name to it.layer }
        val pairs =
            graph.edges
                .map { layerOf.getValue(it.from) to layerOf.getValue(it.to) }
                .filter { it.first != it.second }
                .distinct()
                .sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
        for ((from, to) in pairs) {
            builder.edge("L$from", "L$to")
        }
        return builder.render()
    }
}

/** Renders the message flow of client requests as Mermaid flowcharts. */
public object MessageFlowRenderer {
    /**
     * Renders the flow of the first client command, from the client to the leader, through the distinct messages
     * sent until the leader commits that entry, and back to the client.
     *
     * The client to leader hop has no trace event, so it is added as an edge labelled `ClientRequest`. The leader is
     * the node of the first [LogAppended] with [EntryKind.COMMAND]. The window ends at the first [CommitAdvanced] on
     * the leader that covers the appended index, and only messages sent before that commit are shown. Each distinct
     * `(from, to, kind)` triple is shown once, in first-seen order. The reply edge `ClientReply` is shown only if the
     * commit exists. The result has at most `2 + 7·n·(n−1)` edges for `n` nodes, and fewer in practice.
     *
     * The input list is not modified and its order is ignored because events are processed by `seq`.
     *
     * @param events the recorded trace events, in any order
     * @return the Mermaid `flowchart LR` text
     * @throws IllegalArgumentException if the trace has no client command entry
     */
    public fun clientWrite(events: List<TraceEvent>): String {
        val sorted = events.sortedBy { it.seq }
        val append =
            sorted.filterIsInstance<LogAppended>().firstOrNull { it.entryKind == EntryKind.COMMAND }
                ?: throw IllegalArgumentException("trace has no client command entry")
        val leader = checkNotNull(append.node)
        val commit =
            sorted.filterIsInstance<CommitAdvanced>().firstOrNull {
                it.node == leader && it.commitIndex >= append.index && it.seq > append.seq
            }
        val window =
            sorted.filterIsInstance<MessageSent>().filter {
                it.seq > append.seq && (commit == null || it.seq < commit.seq)
            }
        val triples = window.map { Triple(it.from, it.to, it.kind) }.distinct()

        val others = LinkedHashSet<NodeId>()
        for ((from, to, _) in triples) {
            if (from != leader) others.add(from)
            if (to != leader) others.add(to)
        }

        val builder = FlowchartBuilder(FlowchartBuilder.Direction.LR)
        builder.node("client", "Client")
        builder.subgraph("cluster", "Cluster")
        builder.node(leader.value, leader.value, "cluster")
        for (id in others) {
            builder.node(id.value, id.value, "cluster")
        }
        builder.edge("client", leader.value, "ClientRequest")
        for ((from, to, kind) in triples) {
            builder.edge(from.value, to.value, MermaidText.kind(kind))
        }
        if (commit != null) {
            builder.edge(leader.value, "client", "ClientReply")
        }
        return builder.render()
    }
}
