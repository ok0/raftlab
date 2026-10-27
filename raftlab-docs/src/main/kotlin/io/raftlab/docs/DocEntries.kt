package io.raftlab.docs

import io.raftlab.sim.ScenarioLibrary
import io.raftlab.sim.ScenarioResult
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.viz.LogTableExporter
import io.raftlab.viz.MessageFlowRenderer
import io.raftlab.viz.ModuleGraph
import io.raftlab.viz.ModuleGraphRenderer
import io.raftlab.viz.SequenceDiagramExporter
import io.raftlab.viz.StateDiagramExporter

/** The fixed, ordered list of generator entries; list order is output order. */
internal object DocEntries {
    /** Seed of the normal-election run shown in the docs. */
    const val SEED: Long = 42L

    /** Maximum number of messages rendered in the sequence diagram. */
    const val MAX_MESSAGES: Int = 60

    /** Seed of the lagging-follower-snapshot run shown in replication.md. */
    const val REPLICATION_SEED: Long = SEED

    /** Seed of the leader-crash run shown in roles-leader-crash.md. */
    const val LEADER_CRASH_SEED: Long = SEED

    /** Seed of the partition-heal run shown in partition-heal-log.md. */
    const val PARTITION_SEED: Long = SEED

    /** Seed of the client-write run shown in architecture.md. */
    const val CLIENT_WRITE_SEED: Long = SEED

    /** Milliseconds shown before the InstallSnapshot message in replication.md. */
    const val REPLICATION_BEFORE_MS: Long = 100L

    /** Milliseconds shown after the InstallSnapshot message in replication.md. */
    const val REPLICATION_AFTER_MS: Long = 200L

    /** Maximum number of index columns per time point in partition-heal-log.md. */
    const val LOG_TABLE_COLUMNS: Int = 20

    /** Times at which the logs are shown in partition-heal-log.md. */
    val PARTITION_TIMES: List<Long> = listOf(850L, 1_550L, 3_500L)

    private fun run(
        name: String,
        seed: Long,
    ): ScenarioResult {
        val scenario = ScenarioLibrary.all[name] ?: error("unknown scenario $name")
        val result = scenario.run(seed)
        check(result.passed) { "scenario $name seed $seed did not pass: ${result.error}" }
        return result
    }

    private val replicationRun by lazy { run("lagging-follower-snapshot", REPLICATION_SEED) }
    private val leaderCrashRun by lazy { run("leader-crash", LEADER_CRASH_SEED) }
    private val partitionRun by lazy { run("partition-heal", PARTITION_SEED) }
    private val clientWriteRun by lazy { run("client-write", CLIENT_WRITE_SEED) }

    /** Returns the generator entries in output order, using [moduleGraph] for the module graph docs. */
    fun standard(moduleGraph: ModuleGraph): List<DocEntry> {
        val electionRun = ElectionDocRun.normalElection(SEED)
        val scenario = "the 5-node normal-election run (seed $SEED, default SimOptions)"
        val electionDescription =
            "Messages of $scenario from 0 ms until the new leader has a response from every " +
                "follower to its first AppendEntries round at ${electionRun.window.last} ms, at most $MAX_MESSAGES messages."
        val replication = replicationEntry()
        val leaderCrash = leaderCrashEntry()
        val partition =
            DocEntry(
                fileName = "partition-heal-log.md",
                title = "Logs across a partition and heal",
                description =
                    "Log of every node in the 5-node partition-heal run (seed $PARTITION_SEED) at " +
                        "${PARTITION_TIMES[0]} ms (before the partition at 900 ms), ${PARTITION_TIMES[1]} ms " +
                        "(during it, before the heal at 1600 ms) and ${PARTITION_TIMES[2]} ms (after the heal); " +
                        "a cell is the entry term, bold means committed, last $LOG_TABLE_COLUMNS indexes.",
                blocks = {
                    listOf(
                        DocEntry.Markdown(
                            LogTableExporter().export(partitionRun.trace, PARTITION_TIMES, LOG_TABLE_COLUMNS),
                        ),
                    )
                },
            )
        val architecture =
            DocEntry(
                fileName = "architecture.md",
                title = "Architecture",
                description =
                    "System overview by module layer, and the message flow of the first client write in the " +
                        "3-node client-write run (seed $CLIENT_WRITE_SEED).",
                blocks = {
                    listOf(
                        DocEntry.Markdown("## System overview"),
                        DocEntry.Mermaid(ModuleGraphRenderer.overview(moduleGraph)),
                        DocEntry.Markdown(
                            "## Client write message flow\n\nThe client request to the leader, the distinct messages " +
                                "(sender, receiver, kind) in first-seen order until the leader commits the first " +
                                "client command, and the reply.",
                        ),
                        DocEntry.Mermaid(MessageFlowRenderer.clientWrite(clientWriteRun.trace)),
                    )
                },
            )
        val modules =
            DocEntry(
                fileName = "modules.md",
                title = "Module dependencies",
                description =
                    "Project dependencies (main configurations) between the Gradle modules, grouped by layer, " +
                        "read from build/raftlab/module-graph.json written by ./gradlew writeModuleGraph.",
                blocks = { listOf(DocEntry.Mermaid(ModuleGraphRenderer.dependencies(moduleGraph))) },
            )
        val diagrams =
            listOf(
                DocEntry(
                    fileName = "election.md",
                    title = "Leader election",
                    description = electionDescription,
                    blocks = {
                        listOf(
                            DocEntry.Mermaid(
                                SequenceDiagramExporter().export(
                                    electionRun.events,
                                    window = electionRun.window,
                                    maxMessages = MAX_MESSAGES,
                                ),
                            ),
                        )
                    },
                ),
                DocEntry(
                    fileName = "roles-election.md",
                    title = "Node roles during leader election",
                    description = "Role changes of every node in $scenario, the same run as election.md.",
                    blocks = { listOf(DocEntry.Mermaid(StateDiagramExporter().export(electionRun.events))) },
                ),
                replication,
                leaderCrash,
                partition,
                architecture,
                modules,
            )
        val index =
            DocEntry(
                fileName = "index.md",
                title = "Generated diagrams",
                description =
                    "Every generated file in docs/generated with a one-line description; " +
                        "regenerate with ./gradlew generateDocs.",
                blocks = {
                    listOf(
                        DocEntry.Markdown(
                            diagrams.joinToString("\n") { "- [${it.title}](${it.fileName}): ${it.description}" },
                        ),
                    )
                },
            )
        return diagrams + index
    }

    private fun replicationEntry(): DocEntry {
        val trace = replicationRun.trace
        val delivered = trace.filterIsInstance<MessageDelivered>().map { it.msgId }.toSet()
        val install =
            trace
                .filterIsInstance<MessageSent>()
                .filter { it.kind == MessageKind.INSTALL_SNAPSHOT && it.msgId in delivered }
                .minByOrNull { it.seq }
                ?: error("no delivered INSTALL_SNAPSHOT message in lagging-follower-snapshot seed $REPLICATION_SEED")
        val window = (install.time - REPLICATION_BEFORE_MS)..(install.time + REPLICATION_AFTER_MS)
        return DocEntry(
            fileName = "replication.md",
            title = "Log replication to a lagging follower",
            description =
                "Messages between leader ${install.from.value} and lagging follower ${install.to.value} " +
                    "in the 3-node " +
                    "lagging-follower-snapshot run (seed $REPLICATION_SEED) from ${window.first} to " +
                    "${window.last} ms, " +
                    "at most $MAX_MESSAGES messages: the follower is behind and is brought up to date with " +
                    "InstallSnapshot and AppendEntries.",
            blocks = {
                listOf(
                    DocEntry.Mermaid(
                        SequenceDiagramExporter().export(
                            replicationRun.trace,
                            window = window,
                            nodes = setOf(install.from, install.to),
                            maxMessages = MAX_MESSAGES,
                        ),
                    ),
                )
            },
        )
    }

    private fun leaderCrashEntry(): DocEntry {
        val trace = leaderCrashRun.trace
        val crash =
            trace.filterIsInstance<NodeCrashed>().minByOrNull { it.seq }
                ?: error("no NodeCrashed in leader-crash seed $LEADER_CRASH_SEED")
        val newLeader =
            trace
                .filterIsInstance<RoleChanged>()
                .filter { it.to == Role.LEADER && it.seq > crash.seq && it.node != crash.node }
                .minByOrNull { it.seq }
                ?: error("no new leader after the crash in leader-crash seed $LEADER_CRASH_SEED")
        val restart =
            trace
                .filterIsInstance<NodeRestarted>()
                .filter { it.node == crash.node && it.seq > crash.seq }
                .minByOrNull { it.seq }
        val restartText = restart?.let { ", ${crash.node?.value} restarts at ${it.time} ms" } ?: ""
        return DocEntry(
            fileName = "roles-leader-crash.md",
            title = "Node roles across a leader crash",
            description =
                "Role changes of every node in the 5-node leader-crash run (seed $LEADER_CRASH_SEED): " +
                    "leader ${crash.node?.value} crashes at ${crash.time} ms and " +
                    "${newLeader.node?.value} becomes leader at " +
                    "${newLeader.time} ms$restartText.",
            blocks = { listOf(DocEntry.Mermaid(StateDiagramExporter().export(trace))) },
        )
    }
}
