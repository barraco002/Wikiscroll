package com.github.terrakok.wikwok.ui

import com.github.terrakok.wikwok.data.WikipediaRepository
import com.github.terrakok.wikwok.domain.Article
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

        override suspend fun getRandomArticle(language: String): Result<Article> {
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
        val ids = viewModel.uiState.value.articles.map { it.id }

        repeat(10) { index -> viewModel.onLike(ids[index % ids.size]) }

        assertTrue(viewModel.uiState.value.pendingReward != null, "a reward is owed at ten reads")

        viewModel.dismissReward()
        assertEquals(null, viewModel.uiState.value.pendingReward)
    }
}
