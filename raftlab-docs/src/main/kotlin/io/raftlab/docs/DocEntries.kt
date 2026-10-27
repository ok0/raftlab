package io.raftlab.docs

import io.raftlab.viz.SequenceDiagramExporter
import io.raftlab.viz.StateDiagramExporter

/** The fixed, ordered list of generator entries; list order is output order. */
internal object DocEntries {
    /** Seed of the normal-election run shown in the docs. */
    const val SEED: Long = 42L

    /** Maximum number of messages rendered in the sequence diagram. */
    const val MAX_MESSAGES: Int = 60

    /** Returns the generator entries in output order. */
    fun standard(): List<DocEntry> {
        val run = ElectionDocRun.normalElection(SEED)
        val scenario = "the 5-node normal-election run (seed $SEED, default SimOptions)"
        val electionDescription =
            "Messages of $scenario from 0 ms until the new leader has a response from every " +
                "follower to its first AppendEntries round at ${run.window.last} ms, at most $MAX_MESSAGES messages."
        return listOf(
            DocEntry(
                fileName = "election.md",
                title = "Leader election",
                description = electionDescription,
                diagram = {
                    SequenceDiagramExporter().export(run.events, window = run.window, maxMessages = MAX_MESSAGES)
                },
            ),
            DocEntry(
                fileName = "roles-election.md",
                title = "Node roles during leader election",
                description = "Role changes of every node in $scenario, the same run as election.md.",
                diagram = { StateDiagramExporter().export(run.events) },
            ),
        )
    }
}
