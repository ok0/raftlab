package io.raftlab.sim

import io.raftlab.core.RaftStorage
import io.raftlab.trace.NodeId

/**
 * Selects how the durable storage of each simulated node is created.
 *
 * A [Custom] factory is called once per node when the run starts; the storage it returns survives crashes.
 */
public sealed interface StorageKind {
    /** Every node gets a fresh in-memory storage that survives crashes. */
    public data object InMemory : StorageKind

    /**
     * Storage supplied by a caller-provided factory.
     *
     * @property factory Creates the storage of a node; called once per node when the run starts.
     */
    public class Custom(
        /** Creates the storage of a node; called once per node when the run starts. */
        public val factory: (NodeId) -> RaftStorage,
    ) : StorageKind
}
