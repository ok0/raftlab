package io.raftlab.viz

import io.raftlab.trace.TraceCodec
import io.raftlab.trace.TraceEvent

/**
 * Renders a single self-contained HTML page (inline CSS and JavaScript, no network access) that shows the trace as an
 * event list with Prev, Next and Play/Pause controls and a position indicator. The trace is embedded as
 * [TraceCodec] JSON Lines inside a non-executed `<script type="application/x-ndjson" id="trace-data">` element, with
 * `<`, `>` and `&` written as `<`, `>` and `&`. The output is deterministic.
 */
public object HtmlViewer {
    private const val PLACEHOLDER: String = "__RAFTLAB_TRACE_JSONL__"

    private val template: String = loadTemplate()

    /**
     * Renders the trace viewer page for [events].
     *
     * @param events trace events, embedded in the given order.
     * @return the complete HTML document, ending with a newline.
     */
    public fun render(events: List<TraceEvent>): String {
        val data = embed(TraceCodec.encode(events))
        val i = template.indexOf(PLACEHOLDER)
        return template.substring(0, i) + data + template.substring(i + PLACEHOLDER.length)
    }

    internal fun embed(jsonl: String): String =
        buildString(jsonl.length) {
            for (c in jsonl) {
                when (c) {
                    '<' -> append("\\u003c")
                    '>' -> append("\\u003e")
                    '&' -> append("\\u0026")
                    else -> append(c)
                }
            }
        }

    private fun loadTemplate(): String {
        val stream =
            checkNotNull(HtmlViewer::class.java.getResourceAsStream("viewer.html")) {
                "viewer.html resource missing"
            }
        val text = stream.use { String(it.readAllBytes(), Charsets.UTF_8) }.replace("\r\n", "\n")
        check(text.indexOf(PLACEHOLDER) >= 0 && text.indexOf(PLACEHOLDER) == text.lastIndexOf(PLACEHOLDER)) {
            "viewer.html must contain the placeholder once"
        }
        return text
    }
}
