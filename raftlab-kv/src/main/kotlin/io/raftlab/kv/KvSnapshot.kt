package io.raftlab.kv

import kotlinx.serialization.Serializable

// Full state machine image: key-value data and client sessions.
@Serializable
internal data class KvSnapshot(
    val data: Map<String, String>,
    val sessions: Map<String, KvSession>,
)
