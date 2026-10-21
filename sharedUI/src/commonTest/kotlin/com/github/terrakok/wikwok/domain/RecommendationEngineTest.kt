package com.github.terrakok.wikwok.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [RecommendationEngine].
 *
 * Every case is deterministic: the engine takes a seeded [Random], so a failure is reproducible
 * from the test source alone. The Monte Carlo case is the only statistical one and it is bounded
 * by a tolerance derived from its own sample size.
 */
class RecommendationEngineTest {

    private val topCategory = "Ancient history"

    private fun engine(seed: Long = 42L) = RecommendationEngine(Random(seed))

    private fun dwell(articleId: String, categories: List<String>, durationMs: Long) =
        InteractionEvent.DwellTime(articleId, categories, durationMs)

    private fun swipe(articleId: String, categories: List<String>, durationMs: Long) =
        InteractionEvent.FastSwipe(articleId, categories, durationMs)

    private fun like(articleId: String, categories: List<String>) =
        InteractionEvent.LikeAction(articleId, categories)

    // region Scoring and thresholds

    @Test
    fun dwellAtThresholdAwardsTwoPoints() {
        val state = engine().processEvent(
            event = dwell("en:1", listOf("History"), 8_000L),
            currentState = InterestState(),
            todayEpochDay = 10L,
        )

        assertEquals(2, state.scoreOf("History"))
        assertEquals(1, state.articlesRead)
        assertTrue(state.isInSession("en:1"))
    }

    @Test
    fun dwellBelowThresholdScoresNothingButRecordsSession() {
        val state = engine().processEvent(
            event = dwell("en:1", listOf("History"), 7_999L),
            currentState = InterestState(),
            todayEpochDay = 10L,
        )

        assertEquals(0, state.scoreOf("History"))
        assertTrue(state.isInSession("en:1"), "a short dwell must still burn the article for the session")
        assertEquals(1, state.articlesRead)
    }

    @Test
    fun fastSwipeBelowThresholdSubtractsOne() {
        val state = engine().processEvent(
            event = swipe("en:2", listOf("History"), 1_200L),
            currentState = InterestState(userInterestScores = mapOf("History" to 3)),
            todayEpochDay = 10L,
        )

        assertEquals(2, state.scoreOf("History"))
    }

    @Test
    fun fastSwipeNeverDrivesScoreBelowZero() {
        val state = engine().processEvent(
            event = swipe("en:2", listOf("History"), 100L),
            currentState = InterestState(),
            todayEpochDay = 10L,
        )

        assertEquals(0, state.scoreOf("History"))
    }

    @Test
    fun likeAddsFivePoints() {
        val state = engine().processEvent(
            event = like("en:3", listOf("History", "Italy")),
            currentState = InterestState(userInterestScores = mapOf("History" to 1)),
            todayEpochDay = 10L,
        )

        assertEquals(6, state.scoreOf("History"))
        assertEquals(5, state.scoreOf("Italy"))
    }

    // endregion

    // region Streak and session tracking

    @Test
    fun streakIncrementsOnConsecutiveDays() {
        val engine = engine()
        var state = engine.processEvent(dwell("en:1", listOf("History"), 9_000L), InterestState(), 100L)
        assertEquals(1, state.streak.days)

        state = engine.processEvent(dwell("en:2", listOf("History"), 9_000L), state, 101L)
        assertEquals(2, state.streak.days)
        assertEquals(101L, state.streak.lastVisitEpochDay)
    }

    @Test
    fun streakResetsAfterAGap() {
        val engine = engine()
        var state = engine.processEvent(dwell("en:1", listOf("History"), 9_000L), InterestState(), 100L)
        state = engine.processEvent(dwell("en:2", listOf("History"), 9_000L), state, 103L)

        assertEquals(1, state.streak.days)
    }

    @Test
    fun articlesReadIncrementsOnEveryEventIncludingRejections() {
        val engine = engine()
        var state = InterestState()
        repeat(5) { index ->
            state = engine.processEvent(
                event = if (index % 2 == 0) {
                    dwell("en:$index", listOf("History"), 9_000L)
                } else {
                    swipe("en:$index", listOf("History"), 500L)
                },
                currentState = state,
                todayEpochDay = 10L,
            )
        }

        assertEquals(5, state.articlesRead)
        assertEquals(5, state.sessionHistory.size)
    }

    // endregion

    // region The 70/30 split

    @Test
    fun topCategoryShareIsSeventyPercentOverOneThousandDraws() {
        val state = InterestState(userInterestScores = mapOf(topCategory to 10))

        val relevant = (1..5).map { Article("top:$it", "Top $it", listOf(topCategory)) }
        val novel = (1..5).map { Article("novel:$it", "Novel $it", listOf("Novel $it")) }
        val candidates = relevant + novel

        val engine = engine(seed = 42L)
        var topPicks = 0
        repeat(1_000) {
            val picked = engine.nextArticle(candidates, state)
            if (picked != null && picked.categories.contains(topCategory)) topPicks++
        }

        val share = topPicks / 1_000.0
        assertTrue(
            share in 0.65..0.75,
            "expected top category share between 0.65 and 0.75, was $share",
        )
    }

    // endregion

    // region Rewards

    @Test
    fun rewardIsIdempotentForTheSameArticleCount() {
        val state = InterestState(articlesRead = 10)
        val engine = engine()

        val first = engine.checkReward(state)
        val second = engine.checkReward(state)

        assertTrue(first != null, "a reward is due on the tenth article")
        assertEquals(first, second)
    }

    @Test
    fun noRewardOutsideTheInterval() {
        val engine = engine()

        assertNull(engine.checkReward(InterestState()))
        assertNull(engine.checkReward(InterestState(articlesRead = 9)))
        assertNull(engine.checkReward(InterestState(articlesRead = 19)))
    }

    @Test
    fun rewardIsDueAgainOnTheTwentiethArticle() {
        val engine = engine()

        assertTrue(engine.checkReward(InterestState(articlesRead = 20)) != null)
    }

    // endregion

    // region Selection edges

    @Test
    fun emptyCandidatesReturnNull() {
        assertNull(engine().nextArticle(emptyList(), InterestState()))
    }

    @Test
    fun exhaustedSessionFallsBackToTheGivenList() {
        val state = InterestState(
            userInterestScores = mapOf(topCategory to 5),
            sessionHistory = listOf("top:1", "top:2"),
        )
        val onlyRepeats = listOf(
            Article("top:1", "Top 1", listOf(topCategory)),
            Article("top:2", "Top 2", listOf(topCategory)),
        )

        val picked = engine().nextArticle(onlyRepeats, state)

        assertTrue(picked != null, "the feed must not go empty when every candidate was already served")
    }

    // endregion
}
