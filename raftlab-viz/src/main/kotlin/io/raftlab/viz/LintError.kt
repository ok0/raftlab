package io.raftlab.viz

/**
 * One problem found by [MermaidLint].
 *
 * @property line 1-based line number of the problem
 * @property message Human-readable description
 */
public data class LintError(
    val line: Int,
    val message: String,
)
