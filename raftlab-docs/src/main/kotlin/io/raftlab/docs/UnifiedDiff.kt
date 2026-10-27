package io.raftlab.docs

/** Line-based unified diff used to show stale generated docs. */
internal object UnifiedDiff {
    /** Number of unchanged context lines around each hunk. */
    const val CONTEXT: Int = 3

    /** Returns a unified diff from [old] (on disk) to [new] (generated), or an empty string when they are equal. */
    fun diff(
        name: String,
        old: String,
        new: String,
    ): String {
        if (old == new) return ""
        val a = lines(old)
        val b = lines(new)
        val header = "--- $name (on disk)\n+++ $name (generated)\n"
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val ops = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    ops += ' ' to a[i]
                    i++
                    j++
                }

                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops += '-' to a[i]
                    i++
                }

                else -> {
                    ops += '+' to b[j]
                    j++
                }
            }
        }
        while (i < a.size) {
            ops += '-' to a[i]
            i++
        }
        while (j < b.size) {
            ops += '+' to b[j]
            j++
        }
        val changes = ops.indices.filter { ops[it].first != ' ' }
        if (changes.isEmpty()) return header + "@@ trailing newline differs @@\n"
        val out = StringBuilder(header)
        var k = 0
        while (k < changes.size) {
            val firstChange = changes[k]
            var last = firstChange
            var m = k + 1
            while (m < changes.size && changes[m] <= last + 2 * CONTEXT + 1) {
                last = changes[m]
                m++
            }
            k = m
            val start = maxOf(0, firstChange - CONTEXT)
            val end = minOf(ops.lastIndex, last + CONTEXT)
            val before = ops.subList(0, start)
            val oldBefore = before.count { it.first != '+' }
            val newBefore = before.count { it.first != '-' }
            val range = ops.subList(start, end + 1)
            val oldCount = range.count { it.first != '+' }
            val newCount = range.count { it.first != '-' }
            val oldStart = if (oldCount == 0) oldBefore else oldBefore + 1
            val newStart = if (newCount == 0) newBefore else newBefore + 1
            out.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            for ((tag, text) in range) {
                out.append(tag).append(text).append('\n')
            }
        }
        return out.toString()
    }

    private fun lines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")
}
