package io.raftlab.cli

import io.raftlab.core.RaftStorage
import io.raftlab.sim.DurableStorageProvider
import io.raftlab.storage.FileStorage
import java.nio.file.Path

/**
 * File-backed durable storage provider for the CLI.
 *
 * Registered via ServiceLoader so `restart-all` runs from the CLI (ADR-14).
 */
internal class CliFileStorageProvider : DurableStorageProvider {
    override fun open(dir: Path): RaftStorage = FileStorage(dir)
}
