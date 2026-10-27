package io.raftlab.kv

import kotlinx.serialization.Serializable

// Last applied sequence number and its cached result for one client.
@Serializable
internal data class KvSession(
    val lastSeq: Long,
    val lastResult: KvResult,
)
