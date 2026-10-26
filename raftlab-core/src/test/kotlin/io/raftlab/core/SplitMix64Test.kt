package io.raftlab.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SplitMix64Test {
    @Test
    fun matchesGoldenSequence() {
        val rng = SplitMix64(42)
        List(5) { rng.nextLong() } shouldBe
            listOf(
                -4767286540954276203L,
                2949826092126892291L,
                5139283748462763858L,
                6349198060258255764L,
                701532786141963250L,
            )
    }

    @Test
    fun matchesReferenceVector() {
        SplitMix64(0).nextLong() shouldBe -2152535657050944081L
    }

    @Test
    fun matchesGoldenBoundedSequence() {
        val rng = SplitMix64(42)
        List(5) { rng.nextLong(10) } shouldBe listOf(6L, 5L, 9L, 2L, 5L)
        val other = SplitMix64(42)
        List(5) { other.nextLong(16) } shouldBe listOf(5L, 3L, 2L, 4L, 2L)
    }

    @Test
    fun sameSeedAgrees() {
        val a = SplitMix64(7)
        val b = SplitMix64(7)
        List(1000) { a.nextLong() } shouldBe List(1000) { b.nextLong() }
    }

    @Test
    fun differentSeedsDiffer() {
        val a = SplitMix64(42)
        val b = SplitMix64(43)
        List(5) { a.nextLong() } shouldNotBe List(5) { b.nextLong() }
    }

    @Test
    fun boundedStaysInRange() {
        val rng = SplitMix64(42)
        for (bound in listOf(1L, 2L, 3L, 7L, 10L, 1000L, Long.MAX_VALUE)) {
            repeat(10_000) {
                val v = rng.nextLong(bound)
                (v >= 0L && v < bound) shouldBe true
            }
        }
    }

    @Test
    fun rejectsNonPositiveBound() {
        val rng = SplitMix64(1)
        shouldThrow<IllegalArgumentException> { rng.nextLong(0) }
        shouldThrow<IllegalArgumentException> { rng.nextLong(-1) }
    }
}
