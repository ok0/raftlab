package io.raftlab.docs

/**
 * One generator entry; `diagram` runs the scenario and exporter with fixed parameters and returns raw Mermaid text without fences.
 */
internal class DocEntry(
    val fileName: String,
    val title: String,
    val description: String,
    val diagram: () -> String,
) {
    init {
        require(description.isNotBlank() && '\n' !in description) {
            "description must be one non-blank line: $fileName"
        }
        require(fileName.endsWith(".md") && '/' !in fileName) {
            "fileName must be a plain .md name: $fileName"
        }
    }
}
