package com.github.terrakok.wikwok.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.terrakok.wikwok.data.ProfileStore
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
import com.github.terrakok.wikwok.domain.SessionMetrics
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
 * The profile is persisted, so the model survives process death. Without that the whole engine
 * restarts from zero on every cold start, which makes the personalisation a demo: the second launch
 * would greet a reader who spent ten minutes on history with the same random article the first one
 * did. Storage is optional in the constructor so a test can run the policy without a filesystem,
 * and every production call site passes it.
 *
 * @param nowEpochDay clock used for streaks, injected so tests control the calendar.
 * @param initialTopics topics chosen before the feed existed, applied before the first read.
 * @param profileStore persistence for the interest profile, or null to run without one.
 */
class WikiscrollViewModel(
    private val repository: WikipediaRepository,
    private val engine: RecommendationEngine = RecommendationEngine(),
    private val language: String = DEFAULT_LANGUAGE,
    private val nowEpochDay: () -> Long = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()
    },
    initialTopics: List<String> = emptyList(),
    private val profileStore: ProfileStore? = null,
) : ViewModel() {

    private val _uiState: MutableStateFlow<WikiscrollUiState> = MutableStateFlow(WikiscrollUiState())

    /** Observable state consumed by the feed screen. */
    val uiState: StateFlow<WikiscrollUiState> = _uiState.asStateFlow()

    private val _metrics: MutableStateFlow<SessionMetrics> = MutableStateFlow(SessionMetrics())

    /**
     * Counters for the session so far, consumed only by the metrics panel.
     *
     * A separate flow rather than a field of [WikiscrollUiState]: the feed screen already collects
     * the UI state, and folding the counters in would recompose the pager on every dwell event to
     * paint a panel nobody has open.
     */
    val metrics: StateFlow<SessionMetrics> = _metrics.asStateFlow()

    /**
     * Articles that already contributed to [SessionMetrics.articlesRead].
     *
     * Kept here rather than in the counters so the counters stay a value: the first event of a card
     * is what counts the card, the rest only add to the totals.
     */
    private val meteredIds: MutableSet<String> = mutableSetOf()

    /** Serialises buffer refills so two swipes in a row cannot load the same article twice. */
    private val bufferMutex: Mutex = Mutex()

    init {
        restoreProfile()
        seedInterests(initialTopics)
        start()
    }

    /**
     * Replaces the empty profile with the one stored by the previous run.
     *
     * Runs before [seedInterests] so the onboarding seed still applies on a first launch, and does
     * nothing when there is no stored profile: an unreadable profile degrades to a fresh one, which
     * is the only failure the reader cannot notice.
     */
    private fun restoreProfile() {
        val stored = profileStore?.load() ?: return
        _uiState.update { state -> state.copy(interest = stored) }
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
     * The engine classifies the duration against the time this card needed, so a value that does not
     * qualify is not an error: it is a read that stopped mid-card, and the engine still marks the
     * article as served.
     */
    fun onDwellTime(articleId: String, durationMs: Long) {
        recordEvent(articleId) { article ->
            DwellTime(article.id, article.categories, durationMs, article.expectedReadMs)
        }
    }

    /** Records a swipe that left the card before half of its expected reading time. */
    fun onFastSwipe(articleId: String, durationMs: Long) {
        recordEvent(articleId) { article ->
            FastSwipe(article.id, article.categories, durationMs, article.expectedReadMs)
        }
    }

    /**
     * Articles the reader has liked in this session.
     *
     * A like is one-shot per article: the policy scores it as +5 and has no unlike event, so a
     * second tap would inflate the profile with a signal the reader never meant to give twice.
     *
     * Held as Compose state, and held here rather than in the screen, for two reasons. A tap then
     * invalidates the one button that changed instead of the whole pager, and a rotation no longer
     * repaints every liked heart as unliked.
     */
    val likedIds: MutableState<Set<String>> = mutableStateOf(emptySet())

    /** Articles that already produced an open. One-shot for the same reason as [likedIds]. */
    private val openedIds: MutableSet<String> = mutableSetOf()

    /**
     * Records an explicit like, once per article.
     *
     * An unknown article is not recorded, so a recycled page cannot consume the only allowed tap
     * before the real one arrives.
     */
    fun onLike(articleId: String) {
        if (articleId in likedIds.value) return
        val recorded = recordEvent(articleId) { article -> LikeAction(article.id, article.categories) }
        if (recorded) likedIds.value = likedIds.value + articleId
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
        // Stored immediately: a reader who picks topics and closes the app before the first swipe
        // would otherwise be asked again, because the only other write happens on an interaction.
        persistProfile()
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
        // Existence is checked outside the update because the article list is append only: an id
        // that is missing here cannot appear inside it. The state itself is read inside the update,
        // not before it, so a concurrent event cannot be scored against a stale profile and then
        // overwritten.
        if (_uiState.value.articles.none { article -> article.id == articleId }) return false

        // The event is captured rather than rebuilt afterwards: the lambda receives the article, and
        // a second lookup would produce a second event object for the counters to disagree with.
        var recorded: InteractionEvent? = null
        _uiState.update { state ->
            val article = state.articles.firstOrNull { candidate -> candidate.id == articleId }
                ?: return@update state
            val interaction = event(article)
            recorded = interaction
            val interest = engine.processEvent(interaction, state.interest, nowEpochDay())
            state.copy(
                interest = interest,
                pendingReward = engine.checkReward(interest) ?: state.pendingReward,
            )
        }
        val interaction = recorded ?: return false

        // Counters and persistence run after the state write, not inside it: the update lambda can be
        // retried when another thread wins the compare-and-set, and a retried counter increment would
        // report one event twice.
        val firstEventForArticle = meteredIds.add(articleId)
        _metrics.update { current -> current.recording(interaction, firstEventForArticle) }
        persistProfile()
        return true
    }

    /** Stores the current profile for the next launch. Best effort; see [ProfileStore.save]. */
    private fun persistProfile() {
        profileStore?.save(_uiState.value.interest)
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
