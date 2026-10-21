package com.github.terrakok.wikwok.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.Reward

/**
 * The feed and the article detail, as one screen with two destinations.
 *
 * The pager owns the scroll position and the screen only reacts to it, so the view model never
 * fights the gesture. Two observations feed the engine: the settled page, which tells the buffering
 * logic how far ahead it is, and the dwell tracker, which reports reading time.
 *
 * The detail page is a second destination held in composition state rather than a navigation graph
 * entry. There is one route and one back gesture, so a graph would add a dependency and a lifecycle
 * to model a boolean. The pager state lives outside [AnimatedContent], which is what makes returning
 * to the feed land on the same card instead of the first one.
 *
 * Likes are tracked locally because they are a presentation concern here: the engine only needs to
 * know that the event happened, and it does not echo a per-article flag back. Re-tapping an already
 * liked card replays the animation without recording a second event, which would otherwise inflate
 * the interest score for a double tap.
 *
 * @param viewModel feed state holder.
 * @param modifier applied to the root box.
 */
@Composable
fun WikiscrollScreen(
    viewModel: WikiscrollViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { uiState.articles.size })
    val uriHandler = LocalUriHandler.current
    var likedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedArticle by remember { mutableStateOf<Article?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            viewModel.onSettled(index)
        }
    }

    DwellTrackerEffect(
        pagerState = pagerState,
        articles = uiState.articles,
        onDwell = viewModel::onDwellTime,
        onFastSwipe = viewModel::onFastSwipe,
        active = selectedArticle == null,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AnimatedContent(
            targetState = selectedArticle,
            contentKey = { it?.id },
            transitionSpec = {
                if (targetState != null) {
                    (slideInVertically(initialOffsetY = { it / 6 }) + fadeIn()) togetherWith fadeOut()
                } else {
                    (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith
                        (slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut())
                }
            },
            label = "feedToDetail",
            modifier = Modifier.fillMaxSize(),
        ) { article ->
            if (article == null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        uiState.articles.isEmpty() && uiState.status == FeedStatus.Failed -> FailedState(
                            message = uiState.errorMessage,
                            onRetry = viewModel::retry,
                        )

                        uiState.articles.isEmpty() -> LoadingState()

                        else -> VerticalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                        ) { index ->
                            val current = uiState.articles.getOrNull(index)
                            if (current == null) {
                                Box(modifier = Modifier.fillMaxSize())
                            } else {
                                ArticleCard(
                                    article = current,
                                    isLiked = current.id in likedIds,
                                    onLike = {
                                        if (current.id !in likedIds) {
                                            likedIds = likedIds + current.id
                                            viewModel.onLike(current.id)
                                        }
                                    },
                                    onOpen = { selectedArticle = current },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    RewardBanner(
                        reward = uiState.pendingReward,
                        onDismiss = viewModel::dismissReward,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 12.dp),
                    )
                }
            } else {
                ArticleDetailScreen(
                    article = article,
                    onBack = { selectedArticle = null },
                    onOpenArticle = { uriHandler.openUri(article.canonicalUrl()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Fades a payout in at the top of the feed.
 *
 * The banner is deliberately dismissable by tap and disappears on its own when the next one
 * arrives, so a reward never blocks the swipe.
 */
@Composable
private fun RewardBanner(
    reward: Reward?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<Reward?>(null) }
    LaunchedEffect(reward) {
        if (reward != null) shown = reward
    }
    AnimatedVisibility(
        visible = reward != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier,
    ) {
        val label = shown?.let { rewardLabel(it) }.orEmpty()
        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(percent = 50),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF2D55),
                contentColor = Color.White,
            ),
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun FailedState(
    message: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = "The feed could not load",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message ?: "Check your connection and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Text(text = "Retry")
            }
        }
    }
}

/** Human label for a payout. Kept here because it is copy, not domain knowledge. */
private fun rewardLabel(reward: Reward): String = when (reward) {
    Reward.Badge -> "Badge unlocked"
    Reward.StreakFreeze -> "Streak freeze saved"
    Reward.DeepDive -> "Deep dive unlocked"
    Reward.ThemeUnlock -> "New theme unlocked"
    Reward.Jackpot -> "Jackpot"
}

/**
 * Canonical article URL.
 *
 * The identifier carries the language prefix, which is the only place the edition is known, so the
 * URL is built from it rather than from an extra field.
 */
internal fun Article.canonicalUrl(): String {
    val language = id.substringBefore(':', missingDelimiterValue = "en")
    return "https://" + language + ".wikipedia.org/wiki/" + title.replace(' ', '_')
}
