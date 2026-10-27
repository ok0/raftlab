package io.raftlab.core

/**
 * Deliberately injected safety bugs, for testing only. A mutation is enabled solely via [RaftConfig.mutations]; the
 * default is empty. Documented in `docs/design/mutations.md`.
 */
public enum class Mutation {
    /** Votes are granted without the Raft §5.4.1 log up-to-date check. */
    SKIP_LOG_UP_TO_DATE_CHECK,

    /** The leader advances commit once one replica fewer than a majority (counting itself) stores the entry. */
    COMMIT_WITHOUT_MAJORITY,

    /** The leader commits earlier-term entries by replica count, removing the §5.4.2 current-term rule. */
    COMMIT_PREVIOUS_TERM_ENTRIES,
}
