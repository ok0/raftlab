package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

class InMemoryStorageTest : RaftStorageContract() {
    override fun createStorage(): RaftStorage = InMemoryStorage()

    @Test
    fun survivesSimulatedCrash() {
        val storage = InMemoryStorage()
        storage.saveState(PersistentState(1, NodeId("n1")))
        storage.append(listOf(LogEntry(1, 1, EntryPayload.Command(byteArrayOf(1)))))
        storage.loadState() shouldBe PersistentState(1, NodeId("n1"))
        storage.lastIndex() shouldBe 1L
    }
}
