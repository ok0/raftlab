package io.raftlab.net

import io.raftlab.trace.NodeId
import java.net.InetSocketAddress

/**
 * The static membership of a cluster: each node id mapped to the socket address it listens on.
 *
 * [members] keeps declaration order, which is the order given to [parse].
 */
public data class ClusterConfig(
    /** Member addresses keyed by node id, in declaration order. */
    public val members: Map<NodeId, InetSocketAddress>,
) {
    init {
        require(members.isNotEmpty()) { "cluster must have at least one member" }
    }

    /** All member ids in declaration order. */
    public val ids: Set<NodeId> get() = members.keys

    /** Returns the address of member [id]; throws [IllegalArgumentException] if [id] is not a member. */
    public fun address(id: NodeId): InetSocketAddress = requireNotNull(members[id]) { "unknown node ${id.value}" }

    /** Renders the members as `id=host:port` entries joined by `,`, which [parse] accepts. */
    override fun toString(): String =
        members.entries.joinToString(",") { (id, addr) -> "${id.value}=${addr.hostString}:${addr.port}" }

    /** Parsing of cluster descriptions such as `n1=127.0.0.1:7001,n2=localhost:7002`. */
    public companion object {
        /**
         * Parses a comma-separated list of `id=host:port` entries, keeping declaration order.
         *
         * Surrounding whitespace is ignored. Any malformed entry, empty list, invalid port or duplicate id
         * throws [IllegalArgumentException] and no partial configuration is returned.
         */
        public fun parse(text: String): ClusterConfig {
            require(text.isNotBlank()) { "cluster must not be empty" }
            val out = LinkedHashMap<NodeId, InetSocketAddress>()
            for (raw in text.split(',')) {
                val entry = raw.trim()
                require(entry.isNotEmpty()) { "empty member entry in '$text'" }
                val eq = entry.indexOf('=')
                require(eq > 0) { "member '$entry' must have the form id=host:port" }
                val id = NodeId(entry.substring(0, eq).trim())
                require(id.value.isNotEmpty()) { "member '$entry' must have the form id=host:port" }
                val addr = entry.substring(eq + 1).trim()
                val colon = addr.lastIndexOf(':')
                require(colon > 0) { "member '$entry' must have the form id=host:port" }
                val host = addr.substring(0, colon)
                val port =
                    requireNotNull(addr.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }) {
                        "member '$entry' has an invalid port"
                    }
                require(id !in out) { "duplicate member id '${id.value}'" }
                out[id] = InetSocketAddress(host, port)
            }
            return ClusterConfig(out)
        }
    }
}
