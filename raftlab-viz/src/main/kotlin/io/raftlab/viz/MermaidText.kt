package io.raftlab.viz

import io.raftlab.trace.MessageKind
import io.raftlab.trace.Role

internal object MermaidText {
    private const val LABEL_PUNCTUATION = " _.,=+/()-@!?"

    fun label(text: String): String {
        val mapped =
            buildString {
                for (c in text) {
                    when {
                        c == '\n' || c == '\r' || c == '\t' || c.isWhitespace() -> append(' ')
                        c.isLetterOrDigit() || c in LABEL_PUNCTUATION -> append(c)
                        else -> append('_')
                    }
                }
            }
        return mapped.replace(Regex(" +"), " ").trim()
    }

    fun name(text: String): String {
        val mapped =
            buildString {
                for (c in text) {
                    if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.') {
                        append(c)
                    } else {
                        append('_')
                    }
                }
            }
        return mapped.ifEmpty { "_" }
    }

    fun role(role: Role): String = pascal(role.name)

    fun kind(kind: MessageKind): String = pascal(kind.name)

    private fun pascal(enumName: String): String =
        enumName.split('_').joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
}
