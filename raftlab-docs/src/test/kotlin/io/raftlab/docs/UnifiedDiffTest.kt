package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class UnifiedDiffTest {
    @Test
    fun identicalTextsGiveEmptyDiff() {
        UnifiedDiff.diff("f.md", "a\n", "a\n") shouldBe ""
    }

    @Test
    fun changedLineShowsMinusAndPlus() {
        UnifiedDiff.diff("f.md", "a\nX\nc\n", "a\nb\nc\n") shouldBe
            "--- f.md (on disk)\n+++ f.md (generated)\n@@ -1,3 +1,3 @@\n a\n-X\n+b\n c\n"
    }

    @Test
    fun hunkKeepsThreeContextLines() {
        val old = (1..20).joinToString("\n", postfix = "\n")
        val new = old.replace("\n10\n", "\nten\n")
        val result = UnifiedDiff.diff("f.md", old, new)
        result shouldContain "@@ -7,7 +7,7 @@\n 7\n 8\n 9\n-10\n+ten\n 11\n 12\n 13\n"
        result shouldNotContain " 6\n"
    }

    @Test
    fun trailingNewlineOnly() {
        UnifiedDiff.diff("f", "a", "a\n") shouldBe
            "--- f (on disk)\n+++ f (generated)\n@@ trailing newline differs @@\n"
    }
}
