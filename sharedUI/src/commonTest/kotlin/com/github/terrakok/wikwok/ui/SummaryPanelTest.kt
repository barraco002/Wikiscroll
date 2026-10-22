package com.github.terrakok.wikwok.ui

import com.github.terrakok.wikwok.data.WikipediaRepository
import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.Summarizer
import com.github.terrakok.wikwok.domain.SummaryOutcome
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The panel is a small state machine, and the states differ in what they ask the reader to do: press,
 * wait, read, or fix the configuration. Each test pins one of those transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryPanelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val article = Article(
        id = "en:7",
        title = "Photolithography",
        categories = listOf("Semiconductor fabrication"),
        extract = "Photolithography transfers a pattern onto a substrate.",
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSummaryArrivesAsReadyAndKeepsItsArticle() = runTest {
        val viewModel = viewModelWith(FakeSummarizer { SummaryOutcome.Ready(listOf("a", "b", "c"), "model-x") })

        viewModel.summarize(article)

        val state = viewModel.summary.value
        assertIs<SummaryState.Ready>(state)
        assertEquals("en:7", state.articleId)
        assertEquals(listOf("a", "b", "c"), state.bullets)
        assertEquals("model-x", state.model)
    }

    @Test
    fun thePanelShowsLoadingWhileTheRequestRuns() = runTest {
        val gate = CompletableDeferred<SummaryOutcome>()
        val viewModel = viewModelWith(FakeSummarizer { gate.await() })

        viewModel.summarize(article)
        assertEquals(SummaryState.Loading, viewModel.summary.value)

        gate.complete(SummaryOutcome.Ready(listOf("a"), "model-x"))
        assertIs<SummaryState.Ready>(viewModel.summary.value)
    }

    @Test
    fun aMissingSummarizerAsksForConfiguration() = runTest {
        val viewModel = viewModelWith(null)

        viewModel.summarize(article)

        val state = viewModel.summary.value
        assertIs<SummaryState.Failed>(state)
        assertTrue(state.needsConfiguration, "a build without the feature cannot be retried into working")
        assertEquals(SUMMARY_UNAVAILABLE_REASON, state.reason)
    }

    @Test
    fun anUnconfiguredClientAlsoAsksForConfiguration() = runTest {
        val viewModel = viewModelWith(FakeSummarizer { SummaryOutcome.Unconfigured("no key") })

        viewModel.summarize(article)

        val state = viewModel.summary.value
        assertIs<SummaryState.Failed>(state)
        assertTrue(state.needsConfiguration)
        assertEquals("no key", state.reason)
    }

    @Test
    fun aTransportFailureOffersARetry() = runTest {
        val viewModel = viewModelWith(FakeSummarizer { SummaryOutcome.Failed("boom") })

        viewModel.summarize(article)

        val state = viewModel.summary.value
        assertIs<SummaryState.Failed>(state)
        assertTrue(state.needsConfiguration.not(), "a failed request is worth another attempt")
    }

    @Test
    fun dismissingClearsThePanel() = runTest {
        val viewModel = viewModelWith(FakeSummarizer { SummaryOutcome.Ready(listOf("a"), "model-x") })

        viewModel.summarize(article)
        viewModel.dismissSummary()

        assertEquals(SummaryState.Idle, viewModel.summary.value)
    }

    private fun viewModelWith(summarizer: Summarizer?): WikiscrollViewModel =
        WikiscrollViewModel(repository = StubRepository(), summarizer = summarizer)

    /** Answers the refill loop with unique articles so the buffer fills instead of looping. */
    private class StubRepository : WikipediaRepository {
        private var issued = 0

        private fun next(): Article {
            issued++
            return Article(
                id = "en:" + issued,
                title = "Title " + issued,
                categories = listOf("Science"),
                extract = "x".repeat(400),
            )
        }

        override suspend fun getArticle(language: String, id: String): Result<Article> =
            Result.success(next())

        override suspend fun getRelatedArticles(language: String, currentArticleId: String): Result<List<Article>> =
            Result.success(listOf(next()))

        override suspend fun getPopularArticle(language: String): Result<Article> =
            Result.success(next())
    }

    /** Summarizer whose answer is supplied by the test. */
    private class FakeSummarizer(private val answer: suspend () -> SummaryOutcome) : Summarizer {
        override suspend fun summarize(article: Article, language: String): SummaryOutcome = answer()
    }
}
