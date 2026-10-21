package com.github.terrakok.wikwok.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.github.terrakok.wikwok.domain.Article
import kotlin.time.Clock

/**
 * Reports how long the reader stays on each article of a [pagerState].
 *
 * The measurement opens when a page settles and closes the moment the reader starts dragging, so
 * the interval covers reading time and not the snap animation. A drag that returns to the same page
 * reopens the measurement, which is deliberate: the reader leaving and coming back is the signal,
 * not the page index staying constant.
 *
 * Two callbacks are exposed instead of one so the caller does not have to re-check the thresholds:
 * [onDwell] for stays at or above the dwell threshold, [onFastSwipe] for stays below the fast-swipe
 * threshold. Medium stays produce no event on purpose; the engine has nothing to learn from them
 * beyond the read itself, which the pager reports separately.
 *
 * The timing rules live in [DwellSession] rather than here so they stay testable without a
 * composition.
 *
 * @param pagerState the feed pager. Reading settledPage or isScrollInProgress outside a snapshot
 *   flow would not recompose.
 * @param articles the current feed content, indexed by page. Read through fresh state so appending
 *   to the buffer does not restart the effect.
 * @param onDwell called with the article id and the duration in milliseconds.
 * @param onFastSwipe called with the article id and the duration in milliseconds.
 */
@Composable
fun DwellTrackerEffect(
    pagerState: PagerState,
    articles: List<Article>,
    onDwell: (articleId: String, durationMs: Long) -> Unit,
    onFastSwipe: (articleId: String, durationMs: Long) -> Unit,
) {
    val session = remember { DwellSession { Clock.System.now().toEpochMilliseconds() } }
    val currentArticles by rememberUpdatedState(articles)
    val currentOnDwell by rememberUpdatedState(onDwell)
    val currentOnFastSwipe by rememberUpdatedState(onFastSwipe)

    fun dispatch(finished: DwellReport?) {
        if (finished == null) return
        when {
            finished.isDwell -> currentOnDwell(finished.articleId, finished.durationMs)
            finished.isFastSwipe -> currentOnFastSwipe(finished.articleId, finished.durationMs)
        }
    }

    LaunchedEffect(pagerState, session) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .collect { (scrolling, settledPage) ->
                if (scrolling) {
                    dispatch(session.leave())
                } else {
                    val article = currentArticles.getOrNull(settledPage)
                    when {
                        article == null -> dispatch(session.leave())
                        session.measuringArticleId != article.id -> {
                            dispatch(session.leave())
                            session.arrive(article.id)
                        }
                    }
                }
            }
    }

    DisposableEffect(session) {
        onDispose { dispatch(session.leave()) }
    }
}
