package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import org.junit.jupiter.api.Test

class LinearizabilityCheckerTest {
    private fun lin(h: History) = LinearizabilityChecker.check(h)

    private fun assertLinearizable(r: LinResult) {
        withClue(LinearizabilityChecker.render(r)) { (r is LinResult.Linearizable) shouldBe true }
    }

    private fun rejected(
        h: History,
        key: String,
    ): LinResult.NotLinearizable {
        val r = lin(h)
        withClue(LinearizabilityChecker.render(r)) {
            (r is LinResult.NotLinearizable) shouldBe true
        }
        val nl = r as LinResult.NotLinearizable
        nl.key shouldBe key
        withClue(LinearizabilityChecker.render(r)) {
            (nl.failedAt != null) shouldBe true
        }
        return nl
    }

    private fun staleReadHistory(): History =
        LinHistories.history(
            LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
            LinHistories.done("c1", 2, KvOp.Put("a", "2"), 2, 3, KvResult.Ok(null)),
            LinHistories.done("c2", 1, KvOp.Get("a"), 4, 5, KvResult.Ok("1")),
            LinHistories.done("c3", 1, KvOp.Put("b", "x"), 0, 1, KvResult.Ok(null)),
            LinHistories.done("c3", 2, KvOp.Get("b"), 2, 3, KvResult.Ok("x")),
        )

    @Test
    fun sequentialChainsAreLinearizable() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
                    LinHistories.done("c1", 2, KvOp.Get("a"), 2, 3, KvResult.Ok("1")),
                    LinHistories.done("c1", 3, KvOp.Cas("a", "1", "2"), 4, 5, KvResult.Ok("2")),
                    LinHistories.done("c1", 4, KvOp.Get("a"), 6, 7, KvResult.Ok("2")),
                    LinHistories.done("c1", 5, KvOp.Delete("a"), 8, 9, KvResult.Ok("2")),
                    LinHistories.done("c1", 6, KvOp.Get("a"), 10, 11, KvResult.Ok(null)),
                    LinHistories.done("c1", 7, KvOp.Cas("a", null, "3"), 12, 13, KvResult.Ok("3")),
                    LinHistories.done("c1", 8, KvOp.Cas("a", "3", null), 14, 15, KvResult.Ok(null)),
                    LinHistories.done("c1", 9, KvOp.Get("a"), 16, 17, KvResult.Ok(null)),
                ),
            ),
        )
    }

    @Test
    fun concurrentPutAndGetSeeOldOrNew() {
        for (v in listOf("old", "new")) {
            assertLinearizable(
                lin(
                    LinHistories.history(
                        LinHistories.done("c1", 1, KvOp.Put("a", "old"), 0, 1, KvResult.Ok(null)),
                        LinHistories.done("c1", 2, KvOp.Put("a", "new"), 2, 10, KvResult.Ok(null)),
                        LinHistories.done("c2", 1, KvOp.Get("a"), 3, 9, KvResult.Ok(v)),
                    ),
                ),
            )
        }
    }

    @Test
    fun concurrentWritesReadSeeEitherOrder() {
        for (v in listOf("x", "y")) {
            assertLinearizable(
                lin(
                    LinHistories.history(
                        LinHistories.done("c1", 1, KvOp.Put("a", "x"), 0, 10, KvResult.Ok(null)),
                        LinHistories.done("c2", 1, KvOp.Put("a", "y"), 0, 10, KvResult.Ok(null)),
                        LinHistories.done("c3", 1, KvOp.Get("a"), 11, 12, KvResult.Ok(v)),
                        LinHistories.done("c3", 2, KvOp.Get("a"), 13, 14, KvResult.Ok(v)),
                    ),
                ),
            )
        }
    }

    @Test
    fun casSuccessThenCasFailureReportsActual() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
                    LinHistories.done("c1", 2, KvOp.Cas("a", "1", "2"), 2, 3, KvResult.Ok("2")),
                    LinHistories.done("c2", 1, KvOp.Cas("a", "1", "3"), 4, 5, KvResult.CasFailed("2")),
                ),
            ),
        )
    }

    @Test
    fun deleteOfAbsentKeyReturnsNull() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.done("c1", 1, KvOp.Delete("a"), 0, 1, KvResult.Ok(null)),
                    LinHistories.done("c1", 2, KvOp.Cas("a", "z", "w"), 2, 3, KvResult.CasFailed(null)),
                ),
            ),
        )
    }

    @Test
    fun multiKeyInterleavingIsLinearizable() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 5, KvResult.Ok(null)),
                    LinHistories.done("c2", 1, KvOp.Put("b", "2"), 1, 3, KvResult.Ok(null)),
                    LinHistories.done("c2", 2, KvOp.Get("a"), 4, 8, KvResult.Ok("1")),
                    LinHistories.done("c1", 2, KvOp.Get("b"), 6, 7, KvResult.Ok("2")),
                    LinHistories.done("c3", 1, KvOp.Delete("b"), 9, 10, KvResult.Ok("2")),
                    LinHistories.done("c3", 2, KvOp.Get("b"), 11, 12, KvResult.Ok(null)),
                ),
            ),
        )
    }

    @Test
    fun emptyHistoryIsLinearizable() {
        LinearizabilityChecker.check(History(emptyList())) shouldBe LinResult.Linearizable(0)
    }

    @Test
    fun staleReadIsRejectedOnItsKey() {
        val nl = rejected(staleReadHistory(), "a")
        nl.operations.size shouldBe 3
    }

    @Test
    fun lostCasIsRejected() {
        rejected(
            LinHistories.history(
                LinHistories.done("c1", 1, KvOp.Put("a", "0"), 0, 1, KvResult.Ok(null)),
                LinHistories.done("c1", 2, KvOp.Cas("a", "0", "1"), 2, 6, KvResult.Ok("1")),
                LinHistories.done("c2", 1, KvOp.Cas("a", "0", "2"), 3, 7, KvResult.Ok("2")),
            ),
            "a",
        )
    }

    @Test
    fun readOfUnwrittenValueIsRejected() {
        rejected(
            LinHistories.history(
                LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
                LinHistories.done("c2", 1, KvOp.Get("a"), 2, 3, KvResult.Ok("ghost")),
            ),
            "a",
        )
    }

    @Test
    fun casFailureWithWrongActualIsRejected() {
        rejected(
            LinHistories.history(
                LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
                LinHistories.done("c1", 2, KvOp.Cas("a", "9", "2"), 2, 3, KvResult.CasFailed("7")),
            ),
            "a",
        )
    }

    @Test
    fun readGoingBackInTimeIsRejected() {
        rejected(
            LinHistories.history(
                LinHistories.done("c1", 1, KvOp.Put("a", "A0"), 0, 1, KvResult.Ok(null)),
                LinHistories.done("c1", 2, KvOp.Put("a", "A"), 2, 10, KvResult.Ok(null)),
                LinHistories.done("c2", 1, KvOp.Get("a"), 3, 4, KvResult.Ok("A")),
                LinHistories.done("c3", 1, KvOp.Get("a"), 5, 6, KvResult.Ok("A0")),
            ),
            "a",
        )
    }

    @Test
    fun deleteReturningAbsentValueIsRejected() {
        rejected(
            LinHistories.history(
                LinHistories.done("c1", 1, KvOp.Delete("a"), 0, 1, KvResult.Ok("x")),
            ),
            "a",
        )
    }

    @Test
    fun violatingKeyIsNamedWhenItSortsLast() {
        rejected(
            LinHistories.history(
                LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
                LinHistories.done("c1", 2, KvOp.Get("a"), 2, 3, KvResult.Ok("1")),
                LinHistories.done("c2", 1, KvOp.Get("z"), 0, 1, KvResult.Ok("q")),
            ),
            "z",
        )
    }

    @Test
    fun pendingPutObservedLaterIsLinearizable() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.pending("c1", 1, KvOp.Put("a", "p"), 0),
                    LinHistories.done("c2", 1, KvOp.Get("a"), 5, 6, KvResult.Ok("p")),
                ),
            ),
        )
    }

    @Test
    fun sameHistoryWithoutPendingPutIsRejected() {
        rejected(
            LinHistories.history(
                LinHistories.done("c2", 1, KvOp.Get("a"), 5, 6, KvResult.Ok("p")),
            ),
            "a",
        )
    }

    @Test
    fun pendingPutNeverObservedIsLinearizable() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.pending("c1", 1, KvOp.Put("a", "p"), 0),
                    LinHistories.done("c2", 1, KvOp.Get("a"), 5, 6, KvResult.Ok(null)),
                    LinHistories.done("c2", 2, KvOp.Get("a"), 7, 8, KvResult.Ok(null)),
                ),
            ),
        )
    }

    @Test
    fun pendingOpDoesNotPrecedeLaterOps() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.pending("c1", 1, KvOp.Put("a", "p"), 0),
                    LinHistories.done("c2", 1, KvOp.Put("a", "q"), 5, 6, KvResult.Ok(null)),
                    LinHistories.done("c2", 2, KvOp.Get("a"), 7, 8, KvResult.Ok("q")),
                    LinHistories.done("c2", 3, KvOp.Get("a"), 9, 10, KvResult.Ok("p")),
                ),
            ),
        )
    }

    @Test
    fun pendingCasAssumedAppliedIsLinearizable() {
        assertLinearizable(
            lin(
                LinHistories.history(
                    LinHistories.done("c1", 1, KvOp.Put("a", "1"), 0, 1, KvResult.Ok(null)),
                    LinHistories.pending("c2", 1, KvOp.Cas("a", "1", "2"), 2),
                    LinHistories.done("c3", 1, KvOp.Get("a"), 5, 6, KvResult.Ok("2")),
                    LinHistories.done("c3", 2, KvOp.Cas("a", "2", "3"), 7, 8, KvResult.Ok("3")),
                ),
            ),
        )
    }

    @Test
    fun timeoutResultBehavesAsPending() {
        for (v in listOf<String?>("p", null)) {
            assertLinearizable(
                lin(
                    LinHistories.history(
                        HistoryEntry("c1", 1, KvOp.Put("a", "p"), 0, 3, KvResult.Timeout),
                        LinHistories.done("c2", 1, KvOp.Get("a"), 5, 6, KvResult.Ok(v)),
                    ),
                ),
            )
        }
    }

    @Test
    fun tinyBudgetGivesUnknownAndDefaultIsStable() {
        val h = LinHistories.generate(1)
        (LinearizabilityChecker.check(h, budget = 2) is LinResult.Unknown) shouldBe true
        assertLinearizable(lin(h))
        lin(h) shouldBe LinearizabilityChecker.check(h)
    }

    @Test
    fun unknownIsNotAccepted() {
        val u = LinearizabilityChecker.check(LinHistories.generate(1), budget = 2)
        u.accepted() shouldBe false
        u.accepted(allowUnknown = true) shouldBe true
        LinResult.Linearizable(1).accepted() shouldBe true
    }

    @Test
    fun requireLinearizableTreatsUnknownAsFailure() {
        val h = LinHistories.generate(1)
        val sr = ScenarioResult("custom", 7, emptyList(), h, emptyList(), emptyList(), emptyList(), null)
        val ex = shouldThrow<IllegalStateException> { LinearizabilityChecker.requireLinearizable(sr, budget = 2) }
        (ex.message!!.contains("scenario 'custom' seed 7: UNKNOWN")) shouldBe true
        val unknownOk = LinearizabilityChecker.requireLinearizable(sr, budget = 2, allowUnknown = true)
        (unknownOk is LinResult.Unknown) shouldBe true
        (LinearizabilityChecker.requireLinearizable(sr) is LinResult.Linearizable) shouldBe true
    }

    @Test
    fun sameHistorySameVerdictAndText() {
        val h1 = staleReadHistory()
        lin(h1) shouldBe lin(h1)
        LinearizabilityChecker.render(lin(h1), "s", 1) shouldBe LinearizabilityChecker.render(lin(h1), "s", 1)
    }

    @Test
    fun renderedFailureNamesKeyOperationsScenarioAndSeed() {
        val r = lin(staleReadHistory())
        val text = LinearizabilityChecker.render(r, "client-write", 42)
        (text.startsWith("scenario 'client-write' seed 42: NOT LINEARIZABLE on key 'a'")) shouldBe true
        (text.contains("c2#1 Get(a) invoked 4 returned 5 -> Ok(1)")) shouldBe true
        (text.contains("c1#1 Put(a,1)")) shouldBe true
        (text.contains("search failed at:")) shouldBe true
        (LinearizabilityChecker.render(r).startsWith("NOT LINEARIZABLE")) shouldBe true
    }
}
