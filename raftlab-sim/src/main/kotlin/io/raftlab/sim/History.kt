package io.raftlab.sim

/**
 * The client operations of one run.
 *
 * Entries are ordered by invocation (virtual time, then event order) and there is one entry per operation.
 *
 * @property entries The recorded operations in invocation order.
 */
public data class History(
    /** The recorded operations in invocation order. */
    public val entries: List<HistoryEntry>,
) {
    /** Returns the entries that have not returned yet, in order. */
    public fun pending(): List<HistoryEntry> = entries.filter { it.pending }

    /** Returns the entries that have returned, in order. */
    public fun completed(): List<HistoryEntry> = entries.filter { !it.pending }

    /** Returns the entries issued by the client [clientId], in order. */
    public fun ofClient(clientId: String): List<HistoryEntry> = entries.filter { it.clientId == clientId }
}
