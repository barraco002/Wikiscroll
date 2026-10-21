package com.github.terrakok.wikwok.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.terrakok.wikwok.data.WikipediaRepository
import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.InteractionEvent
import com.github.terrakok.wikwok.domain.InteractionEvent.DwellTime
import com.github.terrakok.wikwok.domain.InteractionEvent.FastSwipe
import com.github.terrakok.wikwok.domain.InteractionEvent.LikeAction
import com.github.terrakok.wikwok.domain.InteractionEvent.ReadMore
import com.github.terrakok.wikwok.domain.InterestState
import com.github.terrakok.wikwok.domain.RecommendationEngine
import com.github.terrakok.wikwok.domain.Reward
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Loading state of the feed. */
enum class FeedStatus {
    /** Nothing is renderable yet. The screen shows a spinner. */
    Loading,

    /** At least one article is available. */
    Ready,

    /** The feed could not start. The screen shows a retry action. */
    Failed,
}

/**
 * What the feed screen renders.
 *
 * @property articles the loaded window, oldest first. The pager indexes into this list directly.
 * @property status coarse loading state, used to decide between spinner, feed and error card.
 * @property interest running profile fed by every interaction. Exposed so the UI can render the
 *   streak and the top categories without recomputing anything.
 * @property pendingReward reward owed to the user, cleared by [WikiscrollViewModel.dismissReward].
 * @property errorMessage last failure recorded for diagnostics, or null. Stale failures are dropped
 *   when a later article loads. The error card shows fixed copy instead: exception text such as
 *   "java.net.UnknownHostException" is a log line, not a sentence to put in front of a reader.
 * @property settledIndex page the user is looking at, as reported by the pager.
 */
data class WikiscrollUiState(
    val articles: List<Article> = emptyList(),
    val status: FeedStatus = FeedStatus.Loading,
    val interest: InterestState = InterestState(),
    val pendingReward: Reward? = null,
    val errorMessage: String? = null,
    val settledIndex: Int = 0,
)

/**
 * Orchestrates the feed: repository reads, engine decisions and interaction bookkeeping.
 *
 * Two ideas drive the design.
 *
 * First, the buffer. A swipe must never wait, so the view model keeps a window of articles ahead
 * of the pager and refills it in the background. The spinner appears only when the window is
 * empty, which is the first launch or a hard failure, never an ordinary scroll.
 *
 * Second, the engine stays authoritative. The view model never decides what comes next; it asks
 * [RecommendationEngine.nextArticle] with the current neighbourhood and the current interest
 * state. Interaction events mutate that state through the same engine, so the ranking and the
 * profile stay consistent.
 *
 * @param repository data source.
 * @param engine recommendation policy. Stateless apart from its random source.
 * @param language wiki edition the feed reads from.
 * @param nowEpochDay clock used for streaks, injected so tests control the calendar.
 * @param initialTopics topics chosen before the feed existed, applied before the first read.
 */
class WikiscrollViewModel(
    private val repository: WikipediaRepository,
    private val engine: RecommendationEngine = RecommendationEngine(),
    private val language: String = DEFAULT_LANGUAGE,
    private val nowEpochDay: () -> Long = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays().toLong()
    },
    initialTopics: List<String> = emptyList(),
) : ViewModel() {

    private val _uiState: MutableStateFlow<WikiscrollUiState> = MutableStateFlow(WikiscrollUiState())

    /** Observable state consumed by the feed screen. */
    val uiState: StateFlow<WikiscrollUiState> = _uiState.asStateFlow()

    /** Serialises buffer refills so two swipes in a row cannot load the same article twice. */
    private val bufferMutex: Mutex = Mutex()

    init {
        seedInterests(initialTopics)
        start()
    }

    /** Loads the first article if the feed is still empty. Safe to call more than once. */
    fun start() {
        if (_uiState.value.articles.isNotEmpty()) return
        fillBuffer()
    }

    /** Drops the error and tries again from an empty feed. */
    fun retry() {
        _uiState.update { it.copy(status = FeedStatus.Loading, errorMessage = null) }
        fillBuffer()
    }

    /**
     * Reports the page the pager settled on.
     *
     * The index is stored as-is and drives the refill decision: the buffer is topped up whenever
     * the remaining tail drops to [PREFETCH_AHEAD].
     */
    fun onSettled(index: Int) {
        _uiState.update { it.copy(settledIndex = index) }
        fillBuffer()
    }

    /**
     * Records a long read.
     *
     * The engine applies [RecommendationEngine.MIN_DWELL_MS] as its own threshold, so a value below
     * it is not an error: it is a read that did not qualify, and the engine still marks the article
     * as served.
     */
    fun onDwellTime(articleId: String, durationMs: Long) {
        recordEvent(articleId) { article -> DwellTime(article.id, article.categories, durationMs) }
    }

    /** Records a swipe that crossed the page in under [RecommendationEngine.FAST_SWIPE_MS]. */
    fun onFastSwipe(articleId: String, durationMs: Long) {
        recordEvent(articleId) { article -> FastSwipe(article.id, article.categories, durationMs) }
    }

    /**
     * Articles that already produced a like or an open.
     *
     * Both are one-shot per article. The policy scores a like as +5 and has no unlike event, so a
     * second tap on the same card would inflate the profile with a signal the reader never meant
     * to give twice. The button keeps toggling its own icon; only the score is capped at one.
     *
     * Reached from the UI thread only, which is why a plain set is enough.
     */
    private val likedIds: MutableSet<String> = mutableSetOf()
    private val openedIds: MutableSet<String> = mutableSetOf()

    /**
     * Records an explicit like, once per article.
     *
     * An unknown article is not recorded, so a recycled page cannot consume the only allowed tap
     * before the real one arrives.
     */
    fun onLike(articleId: String) {
        if (articleId in likedIds) return
        val recorded = recordEvent(articleId) { article -> LikeAction(article.id, article.categories) }
        if (recorded) likedIds.add(articleId)
    }

    /**
     * Records that the reader opened the full article.
     *
     * Emitted before the browser opens: the process can be backgrounded by the deeplink, and an
     * event queued after that may never run.
     *
     * Once per article. At +10 a second open of the same card would outscore a like twice over.
     */
    fun onReadMore(articleId: String) {
        if (articleId in openedIds) return
        val recorded = recordEvent(articleId) { article -> ReadMore(article.id, article.categories) }
        if (recorded) openedIds.add(articleId)
    }

    /**
     * Installs the topics chosen in the onboarding as the opening profile.
     *
     * This is the cold start fix. Without it the first cards are decided by an empty profile, so the
     * engine has nothing to exploit and the reader judges the product on a random article. Seeding
     * gives the exploitation arm something to aim at from the first swipe.
     *
     * Topics are trimmed, deduplicated case-insensitively and capped at [MAX_SEED_TOPICS]. Blank
     * input is ignored rather than stored, because a whitespace key would never match a category.
     *
     * The feed is not rebuilt here: a profile that changes under an already loaded card would make
     * the next pick inconsistent with what is on screen. Callers seed before the first read.
     *
     * @param topics free-text interests, in the reader's own words.
     */
    fun seedInterests(topics: List<String>) {
        val seeded = topics
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_SEED_TOPICS)
        if (seeded.isEmpty()) return

        _uiState.update { state -> state.copy(interest = state.interest.seededWith(seeded)) }
    }

    /** Consumes the pending reward so the UI does not show it twice. */
    fun dismissReward() {
        _uiState.update { it.copy(pendingReward = null) }
    }

    /**
     * Applies one interaction and recomputes the reward slot.
     *
     * An unknown [articleId] is ignored: the pager can report a page that was evicted from the
     * window, and a missing article must not crash the feed.
     */
    private fun recordEvent(articleId: String, event: (Article) -> InteractionEvent): Boolean {
        val current = _uiState.value
        val article = current.articles.firstOrNull { it.id == articleId } ?: return false

        val interest = engine.processEvent(event(article), current.interest, nowEpochDay())
        val reward = engine.checkReward(interest)

        _uiState.update { state ->
            state.copy(
                interest = interest,
                pendingReward = reward ?: state.pendingReward,
            )
        }
        return true
    }

    /** Starts a refill if the buffer is low. Never blocks the caller. */
    private fun fillBuffer() {
        viewModelScope.launch {
            bufferMutex.withLock {
                if (_uiState.value.status == FeedStatus.Failed) return@withLock
                while (needsMore()) {
                    if (!loadNext()) break
                }
            }
        }
    }

    /** True while the loaded tail is shorter than the read-ahead window. */
    private fun needsMore(): Boolean {
        val state = _uiState.value
        return state.articles.size - state.settledIndex <= PREFETCH_AHEAD
    }

    /**
     * Loads one more article into the window.
     *
     * The candidates are the edges of the last loaded article, minus anything already buffered.
     * When the engine has nothing to offer, or the article is a dead end, one of the day's most
     * viewed articles keeps the feed alive: an infinite feed that stops because a page has no links
     * is not infinite, and a random stub in that slot is worse than no card at all.
     *
     * @return true when an article was appended, false when the attempt failed or produced nothing.
     */
    private suspend fun loadNext(): Boolean {
        val state = _uiState.value
        val buffered = state.articles.mapTo(mutableSetOf()) { article -> article.id }
        // The card the candidate set was built from, which is also the one the narrative pathing
        // has to continue: ranking neighbours against a card the reader is not looking at would
        // route the story through the wrong thread.
        val origin = state.articles.lastOrNull()
        val candidates = origin
            ?.neighbours()
            .orEmpty()
            .filter { candidate -> candidate.id !in buffered }

        val picked = engine.nextArticle(candidates, state.interest, origin)
        val result = if (picked == null) {
            repository.getPopularArticle(language)
        } else {
            repository.getArticle(language, picked.id)
        }

        return result.fold(
            onSuccess = { article -> append(article) },
            onFailure = { error ->
                registerFailure(error)
                false
            },
        )
    }

    /**
     * Adds an article to the window, skipping duplicates and promoting the feed to ready.
     *
     * @return true when the window grew. A false return stops the refill loop, which is what keeps
     * a page that only links back to buffered articles from spinning forever.
     */
    private fun append(article: Article): Boolean {
        if (_uiState.value.articles.any { existing -> existing.id == article.id }) return false

        _uiState.update { state ->
            // Re-checked inside the update. The lambda can receive a value that changed after the
            // read above, and a duplicated card would give the pager two pages with one id, which
            // breaks both the settled-page bookkeeping and the like guard.
            if (state.articles.any { existing -> existing.id == article.id }) {
                state
            } else {
                state.copy(
                    articles = state.articles + article,
                    status = FeedStatus.Ready,
                    errorMessage = null,
                )
            }
        }
        return true
    }

    /**
     * Stores a failure.
     *
     * A failure with a usable buffer is kept quiet: the feed keeps scrolling and the user never
     * sees a loader over content that is already on screen. Only an empty window escalates to
     * [FeedStatus.Failed].
     */
    private fun registerFailure(error: Throwable) {
        _uiState.update { state ->
            if (state.articles.isEmpty()) {
                state.copy(status = FeedStatus.Failed, errorMessage = error.message ?: "Unknown error")
            } else {
                state.copy(errorMessage = error.message)
            }
        }
    }

    companion object {
        /** Articles kept loaded ahead of the settled page. */
        const val PREFETCH_AHEAD: Int = 2

        /** Edition used when the caller does not choose one. */
        const val DEFAULT_LANGUAGE: String = "en"

        /** Ceiling on seeded topics, matching what the onboarding grid can hold on one screen. */
        const val MAX_SEED_TOPICS: Int = 10
    }
}
