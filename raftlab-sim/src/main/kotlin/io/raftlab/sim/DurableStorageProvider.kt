package io.raftlab.sim

import io.raftlab.core.RaftStorage
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Opens durable node storage in a directory.
 *
 * Implementations are registered for `java.util.ServiceLoader` in
 * `META-INF/services/io.raftlab.sim.DurableStorageProvider`, for example a wrapper around `FileStorage` of
 * raftlab-storage-file.
 */
public fun interface DurableStorageProvider {
    /** Opens the storage persisted in [dir], creating an empty one when [dir] is absent or empty. */
    public fun open(dir: Path): RaftStorage

    /** Discovery of registered providers. */
    public companion object {
        /** Returns the registered provider whose class name sorts first, or null when none is registered. */
        public fun discover(): DurableStorageProvider? =
            ServiceLoader
                .load(DurableStorageProvider::class.java, DurableStorageProvider::class.java.classLoader)
                .sortedBy { it.javaClass.name }
                .firstOrNull()
    }
}
