package com.github.terrakok.wikwok.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reading estimate and the three bands built on top of it.
 *
 * These are the numbers the whole policy hangs from: a wrong estimate turns every long article into
 * silence and every short one into noise.
 */
class ReadingPolicyTest {

    private fun article(extractChars: Int): Article =
        Article("en:1", "Title", listOf("History"), extract = "a".repeat(extractChars))

    // region The estimate

    @Test
    fun theEstimateFollowsTheLengthOfTheExtract() {
        val short = article(300).expectedReadMs
        val longer = article(600).expectedReadMs

        assertEquals(300L * 1_000L / Article.CHARS_PER_SECOND, short)
        assertTrue(longer > short, "a longer card has to ask for more time")
    }

    @Test
    fun theEstimateIsCappedAtWhatFitsOnScreen() {
        val capped = Article.MAX_VISIBLE_CHARS.toLong() * 1_000L / Article.CHARS_PER_SECOND

        assertEquals(capped, article(6_000).expectedReadMs, "a card shows five lines, not a book")
    }

    @Test
    fun theEstimateHasAFloorForStubShortExtracts() {
        val floor = Article.MIN_VISIBLE_CHARS.toLong() * 1_000L / Article.CHARS_PER_SECOND

        assertEquals(floor, article(10).expectedReadMs)
        assertEquals(Article.DEFAULT_EXPECTED_READ_MS, floor)
    }

    // endregion

    // region The bands

    @Test
    fun reachingTheExpectedTimeIsADwell() {
        assertTrue(ReadingPolicy.isDwell(20_000, 20_000))
        assertFalse(ReadingPolicy.isDwell(19_999, 20_000))
    }

    @Test
    fun aStayUnderHalfTheExpectedTimeIsAFastSwipe() {
        assertTrue(ReadingPolicy.isFastSwipe(9_999, 20_000))
        assertEquals(10_000, ReadingPolicy.fastSwipeThresholdMs(20_000))
        assertFalse(ReadingPolicy.isFastSwipe(10_000, 20_000))
    }

    @Test
    fun aSecondPassIsADeepRead() {
        assertTrue(ReadingPolicy.isDeepRead(40_000, 20_000))
        assertFalse(ReadingPolicy.isDeepRead(39_999, 20_000))
    }

    @Test
    fun aNonPositiveExpectationDoesNotCollapseTheThresholds() {
        assertEquals(0, ReadingPolicy.fastSwipeThresholdMs(0))
        assertFalse(ReadingPolicy.isDwell(0, 0), "zero milliseconds is never a finished card")
        assertTrue(ReadingPolicy.isDwell(1, 0))
    }

    // endregion
}
