package com.github.terrakok.wikwok.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionMetricsTest {

    private fun metrics(): SessionMetrics = SessionMetrics()

    @Test
    fun theFirstEventOfACardCountsTheCard() {
        val after = metrics().recording(
            event = InteractionEvent.DwellTime("en:1", listOf("History"), 20_000L, 20_000L),
            isFirstEventForArticle = true,
        )

        assertEquals(1, after.articlesRead)
        assertEquals(20_000L, after.totalDwellMs)
        assertEquals(0, after.fastSwipes)
    }

    @Test
    fun aSecondEventOnTheSameCardDoesNotCountItTwice() {
        val first = metrics().recording(
            event = InteractionEvent.DwellTime("en:1", listOf("History"), 20_000L, 20_000L),
            isFirstEventForArticle = true,
        )
        val second = first.recording(
            event = InteractionEvent.LikeAction("en:1", listOf("History")),
            isFirstEventForArticle = false,
        )

        assertEquals(1, second.articlesRead, "one card that was read and liked is still one card")
        assertEquals(1, second.likes)
        assertEquals(20_000L, second.totalDwellMs)
    }

    @Test
    fun anEarlyExitIsCountedAgainstTheLengthOfTheCard() {
        val shortCard = metrics().recording(
            event = InteractionEvent.DwellTime("en:1", listOf("History"), 9_000L, 8_000L),
            isFirstEventForArticle = true,
        )
        val longCard = metrics().recording(
            event = InteractionEvent.DwellTime("en:2", listOf("History"), 9_000L, 30_000L),
            isFirstEventForArticle = true,
        )

        assertEquals(0, shortCard.fastSwipes, "nine seconds finishes an eight second card")
        assertEquals(1, longCard.fastSwipes, "nine seconds of a thirty second card is an early exit")
    }

    @Test
    fun openingTheArticleIsCountedSeparately() {
        val after = metrics().recording(
            event = InteractionEvent.ReadMore("en:1", listOf("History")),
            isFirstEventForArticle = true,
        )

        assertEquals(1, after.openMoreCount)
        assertEquals(0, after.likes)
    }

    @Test
    fun theDerivedValuesAreZeroBeforeTheFirstCard() {
        val empty = metrics()

        assertTrue(empty.isEmpty)
        assertEquals(0L, empty.averageDwellMs)
        assertEquals(0.0, empty.fastSwipeShare)
    }

    @Test
    fun theAverageIsPerCardNotPerEvent() {
        val after = metrics()
            .recording(InteractionEvent.LikeAction("en:1", listOf("History")), isFirstEventForArticle = true)
            .recording(
                event = InteractionEvent.DwellTime("en:2", listOf("History"), 30_000L, 30_000L),
                isFirstEventForArticle = true,
            )

        assertEquals(2, after.articlesRead)
        assertEquals(15_000L, after.averageDwellMs, "one like contributes no reading time")
        assertEquals(0.0, after.fastSwipeShare)
    }
}
