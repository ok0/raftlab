package io.raftlab.kv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A key-value operation carried inside a [KvCommand]. */
@Serializable
public sealed interface KvOp {
    /** The key this operation acts on. */
    public val key: String

    /** Stores [value] under [key]. */
    @Serializable
    @SerialName("Put")
    public data class Put(
        /** The key to store. */
        override val key: String,
        /** The value to store. */
        public val value: String,
    ) : KvOp

    /** Reads the value under [key]. */
    @Serializable
    @SerialName("Get")
    public data class Get(
        /** The key to read. */
        override val key: String,
    ) : KvOp

    /** Removes [key] and returns its previous value. */
    @Serializable
    @SerialName("Delete")
    public data class Delete(
        /** The key to remove. */
        override val key: String,
    ) : KvOp

    /** Compare-and-swap on [key]: sets [new] only when the current value equals [expected]. */
    @Serializable
    @SerialName("Cas")
    public data class Cas(
        /** The key to compare and swap. */
        override val key: String,
        /** Expected current value; null means the key must be absent. */
        public val expected: String?,
        /** Value to set; null means delete the key. */
        public val new: String?,
    ) : KvOp
}
