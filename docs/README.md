# raftlab documentation

This index links the design notes, the generated diagrams and the operations and contributing material. The top-level [README](../README.md) gives the overview and the quick start.

## Diagram gallery

Mermaid diagrams are generated from simulator runs by `./gradlew generateDocs`; do not edit the generated files by hand.

- [Index of generated diagrams](generated/index.md)
- [Leader election](generated/election.md)
- [Node roles during leader election](generated/roles-election.md)
- [Log replication to a lagging follower](generated/replication.md)
- [Node roles across a leader crash](generated/roles-leader-crash.md)
- [Logs across a partition and heal](generated/partition-heal-log.md)
- [Module dependencies](generated/modules.md)
- [Architecture](generated/architecture.md)

## Design decisions

Read in this order:

1. [Core and determinism](design/core-and-determinism.md): the `RaftNode` step function, its inputs and effects, and how the simulator and the TCP runtime host it.
2. [Leader election](design/leader-election.md): terms, randomized timeouts, vote granting and the up-to-date check.
3. [Log replication](design/log-replication.md): `AppendEntries`, conflict backtracking and the commit rule.
4. [Persistence and snapshots](design/persistence-and-snapshots.md): the storage contract, the file layout, snapshots and InstallSnapshot.
5. [Client semantics](design/client-semantics.md): sessions, duplicate suppression, leader routing, timeouts and compare-and-set.
6. [Testing](design/testing.md): the test layers, the scenario library, invariants, linearizability, fuzzing and mutation testing.
7. [Membership changes](design/membership.md): single-server membership changes and their busy conditions.
8. [Pre-vote and leadership transfer](design/pre-vote-and-transfer.md): the pre-vote round and `TimeoutNow` transfers.
9. [Linearizable reads (ReadIndex)](design/read-index.md): reads that do not go through the log.
10. [Mutations](design/mutations.md): the injected bugs, their expected symptoms and their detection status.

## Operations

- [Release](release.md): the release workflow, the Docker image and the first-push checklist.
- [Docker image and compose example](../docker/README.md).
- [Cluster smoke script](../scripts/cluster-smoke.sh): starts a three-node cluster over TCP and runs KV operations against it.

## API reference

Generate the Dokka API reference with `./gradlew dokkaGenerate`. The entry page is written to `build/dokka/html/index.html`.

## Contributing

- [Contributing guide](../CONTRIBUTING.md)
