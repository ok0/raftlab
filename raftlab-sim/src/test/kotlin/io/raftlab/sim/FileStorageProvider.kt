package io.raftlab.sim

import io.raftlab.core.RaftStorage
import io.raftlab.storage.FileStorage
import java.nio.file.Path

public class FileStorageProvider : DurableStorageProvider {
    override fun open(dir: Path): RaftStorage = FileStorage(dir)
}
