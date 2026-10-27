package io.raftlab.docs

/**
 * One generator entry: the file name, title, one-line description and body blocks of a generated file.
 *
 * `blocks` runs the scenario and exporters with fixed parameters and returns the body blocks in output order.
 */
internal class DocEntry(
    val fileName: String,
    val title: String,
    val description: String,
    val blocks: () -> List<Block>,
) {
    init {
        require(description.isNotBlank() && '\n' !in description) {
            "description must be one non-blank line: $fileName"
        }
        require(fileName.endsWith(".md") && '/' !in fileName) {
            "fileName must be a plain .md name: $fileName"
        }
    }

    /** One block of a generated file body. */
    sealed interface Block {
        /** Raw text of the block, without fences. */
        val text: String
    }

    /** A Mermaid diagram block; it is linted, then written inside a mermaid fence. */
    data class Mermaid(
        override val text: String,
    ) : Block

    /** A Markdown block; it is copied into the file verbatim. */
    data class Markdown(
        override val text: String,
    ) : Block
}
