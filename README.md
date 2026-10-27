# raftlab

A Raft consensus lab in Kotlin. The full README arrives in a later milestone.

## Architecture

<!-- BEGIN GENERATED:architecture -->
```mermaid
flowchart TB
    n0["Layer 1 (raftlab-cli)"]
    n1["Layer 2 (raftlab-docs, raftlab-viz)"]
    n2["Layer 3 (raftlab-net, raftlab-sim, raftlab-storage-file)"]
    n3["Layer 4 (raftlab-kv)"]
    n4["Layer 5 (raftlab-core)"]
    n5["Layer 6 (raftlab-trace)"]
    n0 --> n1
    n0 --> n2
    n1 --> n2
    n1 --> n5
    n2 --> n3
    n2 --> n4
    n2 --> n5
    n3 --> n4
    n3 --> n5
    n4 --> n5
```
<!-- END GENERATED:architecture -->
