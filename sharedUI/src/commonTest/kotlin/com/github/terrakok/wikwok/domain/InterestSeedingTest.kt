package com.github.terrakok.wikwok.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random

/**
 * Cold start behaviour: the topics chosen before the first card decide what the feed shows.
 *
 * The seeded topics are free text, so the contract under test is the matching rule as much as the
 * seeding: whole words, case-insensitive, and never a partial-word hit.
 */
class InterestSeedingTest {

    @Test
    fun seedingGivesEveryTopicTheSeedWeight() {
        val state = InterestState().seededWith(listOf("History", "Cosmology"))

        assertEquals(InterestState.SEED_INTEREST_POINTS, state.scoreOf("History"))
        assertEquals(InterestState.SEED_INTEREST_POINTS, state.scoreOf("Cosmology"))
    }

    @Test
    fun seedingTwiceDoesNotInflateTheWeight() {
        val once = InterestState().seededWith(listOf("History"))
        val twice = once.seededWith(listOf("History"))

        assertEquals(InterestState.SEED_INTEREST_POINTS, twice.scoreOf("History"))
    }

    @Test
    fun seedingKeepsAHigherLearnedScore() {
        val learned = InterestState(userInterestScores = mapOf("History" to 40))
        val seeded = learned.seededWith(listOf("History"))

        assertEquals(40, seeded.scoreOf("History"), "a learned score outranks the seed weight")
    }

    @Test
    fun aSeededTopicMatchesACategoryByWholeWords() {
        val state = InterestState().seededWith(listOf("Norse mythology"))

        assertEquals(
            InterestState.SEED_INTEREST_POINTS,
            state.matchScore(listOf("Category:Norse mythology", "Category:Germanic paganism")),
        )
    }

    @Test
    fun matchingIgnoresCase() {
        val state = InterestState().seededWith(listOf("norse MYTHOLOGY"))

        assertTrue(state.isPreferred("Category:Norse mythology"))
    }

    @Test
    fun aPartialWordIsNotAMatch() {
        val state = InterestState().seededWith(listOf("art"))

        assertEquals(0, state.matchScore(listOf("Category:Artificial intelligence")))
        assertFalse(state.isPreferred("Category:Bartholomew"))
        assertTrue(state.isPreferred("Category:Art"), "the whole word still matches")
    }

    @Test
    fun aSeededTopicTakesTheCategoryOutOfTheExplorationArm() {
        val state = InterestState().seededWith(listOf("Norse mythology"))

        assertFalse(
            state.isUnexplored("Category:Norse mythology"),
            "an article that answers a stated interest is not serendipity",
        )
        assertTrue(state.isUnexplored("Category:Video games"))
    }

    @Test
    fun seedingRaisesAWeaklyLearnedTopicToTheSeedWeight() {
        val state = InterestState(userInterestScores = mapOf("Italy" to 5)).seededWith(listOf("Italy"))

        assertEquals(InterestState.SEED_INTEREST_POINTS, state.scoreOf("Italy"))
    }

    @Test
    fun everyMatchingCategoryOfOneArticleAddsUp() {
        val state = InterestState().seededWith(listOf("Ancient Rome"))

        assertEquals(
            2 * InterestState.SEED_INTEREST_POINTS,
            state.matchScore(listOf("Category:Ancient Rome", "Category:Ancient Rome by period")),
        )
    }

    @Test
    fun anExactKeyIsCountedOnceEvenWhenItIsAlsoASeededTopic() {
        val state = InterestState(userInterestScores = mapOf("Italy" to 5)).seededWith(listOf("Italy"))

        assertEquals(InterestState.SEED_INTEREST_POINTS, state.matchScore(listOf("Italy")))
    }

    @Test
    fun theEnginePrefersTheArticleThatAnswersASeededTopic() {
        val state = InterestState().seededWith(listOf("mythology"))
        val relevant = Article("en:1", "Norse mythology", listOf("Category:Norse mythology"))
        val irrelevant = Article("en:2", "Video game", listOf("Category:Video games"))
        val candidates = listOf(relevant, irrelevant)

        val picks = (1..60).map { RecommendationEngine(Random(it.toLong())).nextArticle(candidates, state)?.id }

        assertTrue(
            picks.count { it == relevant.id } > picks.count { it == irrelevant.id },
            "the seeded topic must dominate the exploitation arm",
        )
    }
}
