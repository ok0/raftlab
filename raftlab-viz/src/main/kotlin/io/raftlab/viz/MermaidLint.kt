package io.raftlab.viz

/**
 * Lightweight structural checker for the Mermaid subset emitted by the viz exporters.
 *
 * Supported subset:
 * - `stateDiagram-v2` with `state "desc" as id {` / `state id {` blocks closed by `}`, `[*]` and `a --> b : label` transitions.
 * - `sequenceDiagram` with `participant id [as alias]`, `Note over|left of|right of ...: text` and `a->>b: text`, `a-->>b: text`, `a-xb: text`, `a--xb: text`.
 * - `flowchart TB|TD|BT|RL|LR` with `subgraph id["title"]` blocks closed by `end`, `direction`, `id["label"]` nodes and `a --> b`, `a -->|label| b`, `a -.-> b`, `a ==> b` edges.
 *
 * This is not a full Mermaid parser. It reports problems inside the supported subset only, never throws, and
 * returns an empty list when the text is accepted.
 */
public object MermaidLint {
    private val ID = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val FLOWCHART_HEADER = Regex("flowchart (TB|TD|BT|RL|LR)")
    private val STATE_QUOTED = Regex("state \"([^\"]*)\" as (\\S+)( \\{)?")
    private val STATE_BLOCK = Regex("state (\\S+) \\{")
    private val STATE_TRANSITION = Regex("(\\S+) --> (\\S+)(?: : (.*))?")
    private val SEQ_PARTICIPANT = Regex("participant (\\S+)(?: as (.*))?")
    private val SEQ_NOTE = Regex("Note (?:over|left of|right of) ([^:]+): (.*)")
    private val SEQ_MESSAGE = Regex("([A-Za-z0-9_]+)(-->>|->>|--x|-x)([A-Za-z0-9_]+): (.*)")
    private val FLOW_SUBGRAPH = Regex("subgraph (\\S+?)(?:\\[\"([^\"]*)\"\\])?")
    private val FLOW_DIRECTION = Regex("direction (TB|TD|BT|RL|LR)")
    private val FLOW_NODE = Regex("(\\S+?)\\[\"([^\"]*)\"\\]")
    private val FLOW_EDGE = Regex("(\\S+) (-->|-\\.->|==>)(?:\\|([^|]*)\\|)? (\\S+)")
    private const val QUOTED_FORBIDDEN = "\"#;<>{}[]|`"
    private const val LABEL_FORBIDDEN = QUOTED_FORBIDDEN + ":"
    private const val STATE_LABEL_FORBIDDEN = "\";:<>{}[]|"

    private enum class Dialect { STATE, SEQUENCE, FLOWCHART }

    /**
     * Checks [text] against the supported subset and returns the problems found, sorted by line.
     *
     * @param text the Mermaid diagram text to check
     * @return the problems found, empty when the text is accepted
     */
    public fun lint(text: String): List<LintError> {
        if (text.isBlank()) return listOf(LintError(1, "diagram text is empty"))
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val h = lines.indexOfFirst { it.trim().isNotEmpty() }
        if (h < 0) return listOf(LintError(1, "diagram text is empty"))
        val header = lines[h].trim()
        val dialect =
            when {
                header == "stateDiagram-v2" -> Dialect.STATE
                header == "sequenceDiagram" -> Dialect.SEQUENCE
                FLOWCHART_HEADER.matches(header) -> Dialect.FLOWCHART
                else -> return listOf(LintError(h + 1, "unknown or missing diagram header: '${header.take(40)}'"))
            }
        val errors = mutableListOf<LintError>()
        val blocks = ArrayDeque<Int>()
        for (i in h + 1 until lines.size) {
            val t = lines[i].trim()
            val ln = i + 1
            if (t.isEmpty() || t.startsWith("%%")) continue
            val matched =
                when (dialect) {
                    Dialect.STATE -> checkState(ln, t, errors, blocks)
                    Dialect.SEQUENCE -> checkSequence(ln, t, errors)
                    Dialect.FLOWCHART -> checkFlowchart(ln, t, errors, blocks)
                }
            if (!matched) {
                errors += LintError(ln, "unrecognized statement or arrow syntax: '${t.take(40)}'")
            }
        }
        for (o in blocks) {
            val message = if (dialect == Dialect.STATE) "unclosed state block" else "unclosed subgraph"
            errors += LintError(o, message)
        }
        return errors.sortedBy { it.line }
    }

    private fun checkState(
        ln: Int,
        t: String,
        errors: MutableList<LintError>,
        blocks: ArrayDeque<Int>,
    ): Boolean {
        if (t == "}") {
            if (blocks.isEmpty()) {
                errors += LintError(ln, "unmatched '}'")
            } else {
                blocks.removeLast()
            }
            return true
        }
        val quoted = STATE_QUOTED.matchEntire(t)
        if (quoted != null) {
            checkText(errors, ln, quoted.groupValues[1], QUOTED_FORBIDDEN, "state description")
            checkId(errors, ln, quoted.groupValues[2])
            if (quoted.groupValues[3].isNotEmpty()) blocks.addLast(ln)
            return true
        }
        val block = STATE_BLOCK.matchEntire(t)
        if (block != null) {
            checkId(errors, ln, block.groupValues[1])
            blocks.addLast(ln)
            return true
        }
        val transition = STATE_TRANSITION.matchEntire(t)
        if (transition != null) {
            checkEndpoint(errors, ln, transition.groupValues[1])
            checkEndpoint(errors, ln, transition.groupValues[2])
            val label = transition.groups[3]
            if (label != null) checkText(errors, ln, label.value, STATE_LABEL_FORBIDDEN, "transition label")
            return true
        }
        return false
    }

    private fun checkEndpoint(
        errors: MutableList<LintError>,
        ln: Int,
        endpoint: String,
    ) {
        if (endpoint != "[*]") checkId(errors, ln, endpoint)
    }

    private fun checkSequence(
        ln: Int,
        t: String,
        errors: MutableList<LintError>,
    ): Boolean {
        val participant = SEQ_PARTICIPANT.matchEntire(t)
        if (participant != null) {
            checkId(errors, ln, participant.groupValues[1])
            val alias = participant.groups[2]
            if (alias != null) checkText(errors, ln, alias.value, LABEL_FORBIDDEN, "participant alias")
            return true
        }
        val note = SEQ_NOTE.matchEntire(t)
        if (note != null) {
            val participants = note.groupValues[1].split(',').map { it.trim() }
            participants.forEach { checkId(errors, ln, it) }
            if (participants.size > 2) {
                errors += LintError(ln, "note spans more than two participants")
            }
            checkText(errors, ln, note.groupValues[2], LABEL_FORBIDDEN, "note text")
            return true
        }
        val message = SEQ_MESSAGE.matchEntire(t)
        if (message != null) {
            checkId(errors, ln, message.groupValues[1])
            checkId(errors, ln, message.groupValues[3])
            checkText(errors, ln, message.groupValues[4], LABEL_FORBIDDEN, "message text")
            return true
        }
        return false
    }

    private fun checkFlowchart(
        ln: Int,
        t: String,
        errors: MutableList<LintError>,
        blocks: ArrayDeque<Int>,
    ): Boolean {
        if (t == "end") {
            if (blocks.isEmpty()) {
                errors += LintError(ln, "unmatched 'end'")
            } else {
                blocks.removeLast()
            }
            return true
        }
        val subgraph = FLOW_SUBGRAPH.matchEntire(t)
        if (subgraph != null) {
            checkId(errors, ln, subgraph.groupValues[1])
            val title = subgraph.groups[2]
            if (title != null) checkText(errors, ln, title.value, QUOTED_FORBIDDEN, "subgraph title")
            blocks.addLast(ln)
            return true
        }
        if (FLOW_DIRECTION.matches(t)) return true
        val node = FLOW_NODE.matchEntire(t)
        if (node != null) {
            checkId(errors, ln, node.groupValues[1])
            checkText(errors, ln, node.groupValues[2], QUOTED_FORBIDDEN, "node label")
            return true
        }
        val edge = FLOW_EDGE.matchEntire(t)
        if (edge != null) {
            checkId(errors, ln, edge.groupValues[1])
            checkId(errors, ln, edge.groupValues[4])
            val label = edge.groups[3]
            if (label != null) checkText(errors, ln, label.value, LABEL_FORBIDDEN, "edge label")
            return true
        }
        return false
    }

    private fun checkId(
        errors: MutableList<LintError>,
        ln: Int,
        id: String,
    ) {
        if (!ID.matches(id)) {
            errors += LintError(ln, "invalid identifier '$id'")
        }
    }

    private fun checkText(
        errors: MutableList<LintError>,
        ln: Int,
        text: String,
        forbidden: String,
        what: String,
    ) {
        if (text.isBlank()) {
            errors += LintError(ln, "$what is empty")
            return
        }
        val bad = text.firstOrNull { it in forbidden } ?: return
        errors += LintError(ln, "$what contains breaking character '$bad'")
    }
}
