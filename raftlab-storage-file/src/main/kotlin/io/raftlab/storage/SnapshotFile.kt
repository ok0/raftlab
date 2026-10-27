package io.raftlab.storage

import io.raftlab.core.SnapshotMeta
import kotlinx.serialization.Serializable

/**
 * The content of `snapshot.meta.json`; [dataLength] and [dataCrc] (CRC32 value) describe `snapshot.bin`.
 */
@Serializable
internal data class SnapshotFile(
    val meta: SnapshotMeta,
    val dataLength: Long,
    val dataCrc: Long,
)
