package com.github.terrakok.wikwok.ui

import com.github.terrakok.wikwok.data.InterestStateCodec
import com.github.terrakok.wikwok.data.ProfileStore
import com.github.terrakok.wikwok.data.SettingsStorage
import com.github.terrakok.wikwok.data.WikipediaRepository
import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.InterestState
import com.github.terrakok.wikwok.domain.RecommendationEngine
import com.github.terrakok.wikwok.domain.ReadingSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The view model is tested against an in-memory repository, not an HTTP mock: the refill loop is
 * the subject here, and a real network stack would make every assertion a race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WikiscrollViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Deterministic graph: article N links to N*10+1 .. N*10+5, so the feed always has somewhere to
     * go and every id is distinct.
     */
    private class FakeRepository : WikipediaRepository {
        var failures = 0
        var calls = 0

        private fun articleOf(number: Int): Article =
            Article("en:" + number, "Title " + number, listOf("Science")) {
                (1..5).map { edge -> articleOf(number * 10 + edge) }
            }

        override suspend fun getArticle(language: String, id: String): Result<Article> {
            calls++
            if (failures > 0) {
                failures--
                return Result.failure(IllegalStateException("offline"))
            }
            val number = id.substringAfter(':').toIntOrNull() ?: return Result.failure(IllegalArgumentException("bad id"))
            return Result.success(articleOf(number))
        }

        override suspend fun getRelatedArticles(language: String, currentArticleId: String): Result<List<Article>> {
            calls++
            return Result.success(articleOf(1).neighbours())
        }

        override suspend fun getPopularArticle(language: String): Result<Article> {
            calls++
            if (failures > 0) {
                failures--
                return Result.failure(IllegalStateException("offline"))
            }
            return Result.success(articleOf(900))
        }
    }

    @Test
    fun startupFillsThePrefetchWindow() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val state = viewModel.uiState.value

        assertEquals(FeedStatus.Ready, state.status)
        assertEquals(WikiscrollViewModel.PREFETCH_AHEAD + 1, state.articles.size)
        assertEquals(state.articles.size, state.articles.map { it.id }.toSet().size, "no duplicates")
    }

    @Test
    fun settledPageTopsTheWindowUp() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        assertEquals(3, viewModel.uiState.value.articles.size)

        viewModel.onSettled(2)

        assertEquals(5, viewModel.uiState.value.articles.size, "two articles ahead of page 2")
        val ids = viewModel.uiState.value.articles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun likeAddsFivePointsToEveryCategory() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val article = viewModel.uiState.value.articles.first()

        viewModel.onLike(article.id)

        assertEquals(1, viewModel.uiState.value.interest.articlesRead)
        assertEquals(5, viewModel.uiState.value.interest.scoreOf("Science"))
    }

    @Test
    fun unknownArticleIsIgnored() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val before = viewModel.uiState.value.interest

        viewModel.onLike("en:never-loaded")

        assertEquals(before, viewModel.uiState.value.interest)
    }

    @Test
    fun failedStartupIsReportedAsFailed() = runTest(dispatcher.scheduler) {
        val repository = FakeRepository().apply { failures = 1 }
        val viewModel = WikiscrollViewModel(repository)

        assertEquals(FeedStatus.Failed, viewModel.uiState.value.status)
        assertEquals("offline", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun retryAfterFailureRecovers() = runTest(dispatcher.scheduler) {
        val repository = FakeRepository().apply { failures = 1 }
        val viewModel = WikiscrollViewModel(repository)
        assertEquals(FeedStatus.Failed, viewModel.uiState.value.status)

        viewModel.retry()

        assertEquals(FeedStatus.Ready, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.articles.isNotEmpty())
    }

    @Test
    fun duplicateArticleStopsTheRefillLoop() = runTest(dispatcher.scheduler) {
        val repository = object : WikipediaRepository by FakeRepository() {
            override suspend fun getArticle(language: String, id: String): Result<Article> =
                Result.success(Article("en:900", "Title 900", listOf("Science")) { emptyList() })
        }
        val viewModel = WikiscrollViewModel(repository)

        assertEquals(1, viewModel.uiState.value.articles.size, "a self-linking feed must not spin")
    }

    @Test
    fun rewardIsEmittedEveryTenInteractions() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())

        // Ten distinct cards, because an interaction is recorded once per article: repeating a
        // like on the same card cannot stand in for ten reads.
        viewModel.onSettled(8)
        val ids = viewModel.uiState.value.articles.map { it.id }
        assertTrue(ids.size >= 10, "the fixture must expose ten distinct cards, got " + ids.size)

        repeat(10) { index -> viewModel.onLike(ids[index]) }

        assertTrue(viewModel.uiState.value.pendingReward != null, "a reward is owed at ten reads")

        viewModel.dismissReward()
        assertEquals(null, viewModel.uiState.value.pendingReward)
    }

    @Test
    fun repeatedLikesOnOneArticleScoreOnce() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val id = viewModel.uiState.value.articles.first().id

        repeat(5) { viewModel.onLike(id) }

        assertEquals(
            RecommendationEngine.SCORE_LIKE_POINTS,
            viewModel.uiState.value.interest.scoreOf("Science"),
            "a like is recorded once per article",
        )
    }

    @Test
    fun repeatedReadMoreOnOneArticleScoresOnce() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val id = viewModel.uiState.value.articles.first().id

        repeat(5) { viewModel.onReadMore(id) }

        assertEquals(
            RecommendationEngine.SCORE_READ_MORE_POINTS,
            viewModel.uiState.value.interest.scoreOf("Science"),
            "opening the same article twice must not outscore a like",
        )
    }

    @Test
    fun seededTopicsBecomeTheOpeningProfile() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())

        viewModel.seedInterests(listOf("Norse mythology", "Cosmology"))

        val interest = viewModel.uiState.value.interest
        assertEquals(InterestState.SEED_INTEREST_POINTS, interest.scoreOf("Norse mythology"))
        assertEquals(InterestState.SEED_INTEREST_POINTS, interest.scoreOf("Cosmology"))
    }

    @Test
    fun seedingTrimsRepeatsAndBlanks() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())

        viewModel.seedInterests(listOf("  History  ", "", "   ", "history"))

        val scores = viewModel.uiState.value.interest.userInterestScores
        assertEquals(mapOf("History" to InterestState.SEED_INTEREST_POINTS), scores)
    }

    @Test
    fun seedingCapsTheNumberOfTopics() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())

        viewModel.seedInterests((1..50).map { "Topic " + it })

        assertEquals(
            WikiscrollViewModel.MAX_SEED_TOPICS,
            viewModel.uiState.value.interest.userInterestScores.size,
        )
    }

    // region Persistence

    /** In-memory settings backend, standing in for the four platform implementations. */
    private class MemoryStorage : SettingsStorage {
        override var isOnboardingCompleted: Boolean = false
        override var savedProfile: String? = null
    }

    @Test
    fun aLearnedProfileComesBackOnTheNextLaunch() = runTest(dispatcher.scheduler) {
        val storage = MemoryStorage()
        val first = WikiscrollViewModel(FakeRepository(), profileStore = ProfileStore(storage))
        first.onLike(first.uiState.value.articles.first().id)
        assertEquals(
            RecommendationEngine.SCORE_LIKE_POINTS,
            first.uiState.value.interest.scoreOf("Science"),
        )

        val second = WikiscrollViewModel(FakeRepository(), profileStore = ProfileStore(storage))

        assertEquals(
            RecommendationEngine.SCORE_LIKE_POINTS,
            second.uiState.value.interest.scoreOf("Science"),
            "the weight learned before the restart must survive it",
        )
        assertEquals(1, second.uiState.value.interest.articlesRead)
    }

    @Test
    fun theFirstLaunchHasNothingToRestore() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository(), profileStore = ProfileStore(MemoryStorage()))

        assertTrue(viewModel.uiState.value.interest.userInterestScores.isEmpty())
    }

    @Test
    fun aStoredProfileKeepsItsWeightsAndLosesYesterdayPenalties() = runTest(dispatcher.scheduler) {
        val storage = MemoryStorage()
        storage.savedProfile = InterestStateCodec.encode(
            InterestState(
                userInterestScores = mapOf("History" to 9),
                articlesRead = 12,
                recentlyRejectedCategories = mapOf("Sport" to 3),
                recentSignals = listOf(ReadingSignal.FastSwipe),
            ),
        )

        val viewModel = WikiscrollViewModel(FakeRepository(), profileStore = ProfileStore(storage))
        val interest = viewModel.uiState.value.interest

        assertEquals(9, interest.scoreOf("History"))
        assertEquals(12, interest.articlesRead)
        assertTrue(interest.recentlyRejectedCategories.isEmpty(), "the block expired with the process")
        assertTrue(interest.recentSignals.isEmpty(), "the band starts neutral")
    }

    @Test
    fun seedingIsStoredBeforeTheFirstSwipe() = runTest(dispatcher.scheduler) {
        val storage = MemoryStorage()
        val viewModel = WikiscrollViewModel(FakeRepository(), profileStore = ProfileStore(storage))
        assertTrue(storage.savedProfile == null, "nothing is stored until there is something to store")

        viewModel.seedInterests(listOf("Cosmology"))

        val restored = ProfileStore(storage).load()
        assertEquals(InterestState.SEED_INTEREST_POINTS, restored?.scoreOf("Cosmology"))
    }

    // endregion

    // region Session metrics

    @Test
    fun twoEventsOnOneCardCountOneCard() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val id = viewModel.uiState.value.articles.first().id

        viewModel.onLike(id)
        viewModel.onReadMore(id)

        val metrics = viewModel.metrics.value
        assertEquals(1, metrics.articlesRead)
        assertEquals(1, metrics.likes)
        assertEquals(1, metrics.openMoreCount)
    }

    @Test
    fun anEarlyExitIsCountedAgainstTheFixtureCardLength() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val id = viewModel.uiState.value.articles.first().id

        // The fixture articles carry no extract, so the expected time is the shortest readable card:
        // sixteen seconds, which makes anything under eight seconds an early exit.
        viewModel.onFastSwipe(id, 2_000L)

        val metrics = viewModel.metrics.value
        assertEquals(1, metrics.articlesRead)
        assertEquals(1, metrics.fastSwipes)
        assertEquals(2_000L, metrics.totalDwellMs)
    }

    @Test
    fun aCardReadToTheEndIsNotAnEarlyExit() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())
        val id = viewModel.uiState.value.articles.first().id

        viewModel.onDwellTime(id, 17_000L)

        assertEquals(0, viewModel.metrics.value.fastSwipes)
        assertEquals(17_000L, viewModel.metrics.value.totalDwellMs)
    }

    @Test
    fun anIgnoredEventLeavesTheCountersAlone() = runTest(dispatcher.scheduler) {
        val viewModel = WikiscrollViewModel(FakeRepository())

        viewModel.onLike("en:never-loaded")

        assertTrue(viewModel.metrics.value.isEmpty, "a card that was never on screen cannot be counted")
    }

    // endregion
}
