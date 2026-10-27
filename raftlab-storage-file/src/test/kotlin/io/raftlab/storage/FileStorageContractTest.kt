package io.raftlab.storage

import io.raftlab.core.RaftStorage
import io.raftlab.core.RaftStorageContract
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class FileStorageContractTest : RaftStorageContract() {
    @TempDir
    lateinit var tmp: Path

    private var n = 0

    override fun createStorage(): RaftStorage = FileStorage(tmp.resolve("s${++n}"))

    override fun reopen(storage: RaftStorage): RaftStorage = FileStorage((storage as FileStorage).dir)
}
