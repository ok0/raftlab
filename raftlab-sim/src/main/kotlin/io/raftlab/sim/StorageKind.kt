package io.raftlab.sim

import io.raftlab.core.RaftStorage
import io.raftlab.trace.NodeId

/**
 * Selects how the durable storage of each simulated node is created.
 *
 * A [Custom] factory is called once per node when the run starts; the storage it returns survives crashes.
 * [Durable] storage is opened per node at start and reopened at every restart.
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

    /**
     * Durable storage: each node gets its own directory under a temporary directory private to the run; the storage is
     * opened at start and opened again from that directory on every restart, so a restarted node sees only what was
     * persisted. The temporary directory is deleted when the run ends and never appears in the trace.
     *
     * @property provider Opens a node's storage, or null to use [DurableStorageProvider.discover] when the run starts.
     */
    public class Durable(
        /** Opens a node's storage, or null to use [DurableStorageProvider.discover] when the run starts. */
        public val provider: DurableStorageProvider? = null,
    ) : StorageKind
}
