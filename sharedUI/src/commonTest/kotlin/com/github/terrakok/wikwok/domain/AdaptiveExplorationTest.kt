package com.github.terrakok.wikwok.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The adaptive exploration band.
 *
 * The rate is a pure function of the signal window, so most cases are exact arithmetic. The two
 * statistical cases pin the behaviour of the engine against the neutral split documented in the
 * README: whatever the band says, the arm must follow it.
 */
class AdaptiveExplorationTest {

    private val topCategory = "History"

    private fun engine(seed: Long = 7L) = RecommendationEngine(Random(seed))

    private fun stateWith(vararg signals: ReadingSignal) = InterestState(
        userInterestScores = mapOf(topCategory to 10),
        recentSignals = signals.toList(),
    )

    private fun fastSwipes(count: Int) = List(count) { ReadingSignal.FastSwipe }

    private fun dwells(count: Int) = List(count) { ReadingSignal.Dwell }

    // region The rate itself

    @Test
    fun anEmptyWindowUsesTheNeutralSplit() {
        assertEquals(0.30, InterestState().explorationRate, 1e-9)
    }

    @Test
    fun aBalancedWindowStaysAtTheNeutralSplit() {
        val state = stateWith(*(dwells(5) + fastSwipes(5)).toTypedArray())

        assertEquals(0.30, state.explorationRate, 1e-9)
    }

    @Test
    fun aFullWindowOfFastSwipesReachesTheCeiling() {
        val state = stateWith(*fastSwipes(InterestState.SIGNAL_WINDOW).toTypedArray())

        assertEquals(0.40, state.explorationRate, 1e-9)
        assertEquals(10, state.recentFastSwipes)
        assertEquals(0, state.recentDwells)
    }

    @Test
    fun aFullWindowOfDwellsReachesTheFloor() {
        val state = stateWith(*dwells(InterestState.SIGNAL_WINDOW).toTypedArray())

        assertEquals(0.15, state.explorationRate, 1e-9)
        assertEquals(10, state.recentDwells)
        assertEquals(0, state.recentFastSwipes)
    }

    @Test
    fun aSingleRejectionIsNotEnoughToSaturateTheBand() {
        val state = stateWith(ReadingSignal.FastSwipe)

        assertEquals(0.31, state.explorationRate, 1e-9)
    }

    @Test
    fun theWindowForgetsTheOldestSignal() {
        val full = stateWith(*(dwells(9) + fastSwipes(1)).toTypedArray())
        val shifted = full.withSignal(ReadingSignal.FastSwipe)

        assertEquals(10, shifted.recentSignals.size, "the window is capped")
        assertEquals(8, shifted.recentDwells)
        assertEquals(2, shifted.recentFastSwipes)
        assertEquals(0.21, shifted.explorationRate, 1e-9)
    }

    // endregion

    // region What feeds the window

    @Test
    fun aDwellEventRecordsADwellSignal() {
        val state = engine().processEvent(
            InteractionEvent.DwellTime("en:1", listOf(topCategory), 9_000L, expectedDurationMs = 8_000L),
            InterestState(),
            100L,
        )

        assertEquals(listOf(ReadingSignal.Dwell), state.recentSignals)
    }

    @Test
    fun aFastSwipeEventRecordsAFastSwipeSignal() {
        val state = engine().processEvent(
            InteractionEvent.FastSwipe("en:1", listOf(topCategory), 400L),
            InterestState(),
            100L,
        )

        assertEquals(listOf(ReadingSignal.FastSwipe), state.recentSignals)
    }

    @Test
    fun aLikeRecordsADwellSignal() {
        val state = engine().processEvent(
            InteractionEvent.LikeAction("en:1", listOf(topCategory)),
            InterestState(),
            100L,
        )

        assertEquals(listOf(ReadingSignal.Dwell), state.recentSignals)
    }

    @Test
    fun aMediumStayRecordsNoSignal() {
        val state = engine().processEvent(
            InteractionEvent.DwellTime("en:1", listOf(topCategory), 5_600L, expectedDurationMs = 8_000L),
            InterestState(),
            100L,
        )

        assertTrue(state.recentSignals.isEmpty(), "a medium stay is silence, not a vote")
        assertEquals(1, state.articlesRead, "the card still counts as served")
    }

    @Test
    fun aTimedOutSwipeSignalsRejectionWithoutScoring() {
        val state = engine().processEvent(
            InteractionEvent.DwellTime("en:1", listOf(topCategory), 500L),
            InterestState(sessionHistory = listOf("en:0")),
            100L,
        )

        assertEquals(0, state.scoreOf(topCategory), "a short stay is not scored twice")
        assertEquals(listOf(ReadingSignal.FastSwipe), state.recentSignals, "but it does steer the band")
    }

    // endregion

    // region The engine follows the band

    @Test
    fun swipeStressIncreasesTheExploredShare() {
        val candidates = (1..10).map { Article("top:" + it, "Top " + it, listOf(topCategory)) } +
            (1..10).map { Article("novel:" + it, "Novel " + it, listOf("Novel " + it)) }
        val stressed = stateWith(*fastSwipes(InterestState.SIGNAL_WINDOW).toTypedArray())
        val neutral = stateWith()

        val stressedShare = exploredShare(candidates, stressed)
        val neutralShare = exploredShare(candidates, neutral)

        assertTrue(
            stressedShare > neutralShare + 0.05,
            "swipe stress must widen the exploration arm: neutral=$neutralShare stressed=$stressedShare",
        )
        assertTrue(
            stressedShare in 0.33..0.47,
            "expected roughly forty percent exploration under stress, was $stressedShare",
        )
    }

    @Test
    fun sustainedReadingNarrowsTheExploredShare() {
        val candidates = (1..10).map { Article("top:" + it, "Top " + it, listOf(topCategory)) } +
            (1..10).map { Article("novel:" + it, "Novel " + it, listOf("Novel " + it)) }
        val engaged = stateWith(*dwells(InterestState.SIGNAL_WINDOW).toTypedArray())

        val share = exploredShare(candidates, engaged)

        assertTrue(
            share in 0.10..0.22,
            "expected roughly fifteen percent exploration for a satisfied reader, was $share",
        )
    }

    /** Share of draws that landed on a novel category, which is the exploration arm here. */
    private fun exploredShare(candidates: List<Article>, state: InterestState): Double {
        val engine = engine(seed = 2024L)
        var explored = 0
        repeat(1_000) {
            val picked = engine.nextArticle(candidates, state)
            if (picked != null && picked.categories.none { it == topCategory }) explored++
        }
        return explored / 1_000.0
    }

    // endregion
}
