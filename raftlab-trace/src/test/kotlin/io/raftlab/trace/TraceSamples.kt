package io.raftlab.trace

internal object TraceSamples {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")

    val all: List<TraceEvent> =
        listOf(
            NodeStarted(0, 0, n1),
            RoleChanged(1, 150, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
            MessageSent(2, 150, n1, 1, n1, n2, MessageKind.REQUEST_VOTE, 1, "lastLog=0/0"),
            MessageDuplicated(3, 150, null, 1, 2),
            MessageDelivered(4, 160, n2, 1),
            MessageDropped(5, 165, null, 2, DropReason.RANDOM),
            VoteCast(6, 170, n2, 1, n1, true),
            LogAppended(7, 180, n1, 1, 1, EntryKind.NOOP, "noop"),
            LogTruncated(8, 190, n3, 2),
            CommitAdvanced(9, 200, n1, 1),
            EntryApplied(10, 200, n1, 1),
            SnapshotTaken(11, 210, n1, 1, 1),
            SnapshotInstalled(12, 220, n3, 1, 1),
            ConfigChanged(13, 230, n1, listOf(n1, n2, n3)),
            LeadershipTransfer(14, 240, n1, n2),
            ClientInvoked(15, 250, null, "c1", 1, "put x=1"),
            ClientReturned(16, 260, null, "c1", 1, "ok"),
            PartitionChanged(17, 270, null, listOf(listOf(n1, n2), listOf(n3))),
            NodeCrashed(18, 280, n3),
            NodeRestarted(19, 290, n3),
            Marker(20, 300, null, "end"),
        )

    val golden: String =
        listOf(
            """{"type":"NodeStarted","seq":0,"time":0,"node":"n1"}""",
            """{"type":"RoleChanged","seq":1,"time":150,"node":"n1","from":"FOLLOWER","to":"CANDIDATE","term":1,"reason":"election timeout"}""",
            """{"type":"MessageSent","seq":2,"time":150,"node":"n1","msgId":1,"from":"n1","to":"n2","kind":"REQUEST_VOTE","term":1,"summary":"lastLog=0/0"}""",
            """{"type":"MessageDuplicated","seq":3,"time":150,"node":null,"msgId":1,"copyId":2}""",
            """{"type":"MessageDelivered","seq":4,"time":160,"node":"n2","msgId":1}""",
            """{"type":"MessageDropped","seq":5,"time":165,"node":null,"msgId":2,"reason":"RANDOM"}""",
            """{"type":"VoteCast","seq":6,"time":170,"node":"n2","term":1,"candidate":"n1","granted":true}""",
            """{"type":"LogAppended","seq":7,"time":180,"node":"n1","index":1,"term":1,"entryKind":"NOOP","summary":"noop"}""",
            """{"type":"LogTruncated","seq":8,"time":190,"node":"n3","fromIndex":2}""",
            """{"type":"CommitAdvanced","seq":9,"time":200,"node":"n1","commitIndex":1}""",
            """{"type":"EntryApplied","seq":10,"time":200,"node":"n1","index":1}""",
            """{"type":"SnapshotTaken","seq":11,"time":210,"node":"n1","lastIndex":1,"lastTerm":1}""",
            """{"type":"SnapshotInstalled","seq":12,"time":220,"node":"n3","lastIndex":1,"lastTerm":1}""",
            """{"type":"ConfigChanged","seq":13,"time":230,"node":"n1","members":["n1","n2","n3"]}""",
            """{"type":"LeadershipTransfer","seq":14,"time":240,"node":"n1","target":"n2"}""",
            """{"type":"ClientInvoked","seq":15,"time":250,"node":null,"clientId":"c1","opId":1,"op":"put x=1"}""",
            """{"type":"ClientReturned","seq":16,"time":260,"node":null,"clientId":"c1","opId":1,"result":"ok"}""",
            """{"type":"PartitionChanged","seq":17,"time":270,"node":null,"groups":[["n1","n2"],["n3"]]}""",
            """{"type":"NodeCrashed","seq":18,"time":280,"node":"n3"}""",
            """{"type":"NodeRestarted","seq":19,"time":290,"node":"n3"}""",
            """{"type":"Marker","seq":20,"time":300,"node":null,"label":"end"}""",
        ).joinToString("") { it + "\n" }
}
