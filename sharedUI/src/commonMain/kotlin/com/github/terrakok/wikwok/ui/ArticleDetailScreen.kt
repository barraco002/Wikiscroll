package com.github.terrakok.wikwok.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.terrakok.wikwok.LocalImageLoader
import com.github.terrakok.wikwok.domain.Article

/**
 * Long-form view of one article.
 *
 * The whole page scrolls, header image included, because a fixed hero would steal a third of a
 * phone screen from the text the reader came here for. The app bar floats over the image with a
 * transparent container so the headline is the first thing that reads as content.
 *
 * The body is the plain-text extract rather than rendered HTML: the API returns text, and a
 * half-parser would break more articles than it improves. The button at the end is the escape hatch
 * to the real page.
 *
 * @param article content to render.
 * @param onBack invoked when the reader leaves the detail.
 * @param onOpenArticle invoked when the reader asks for the original page.
 * @param summaryState state of the summarization action for this article.
 * @param onSummarize invoked when the reader asks Claude for a summary.
 * @param modifier applied to the root box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    article: Article,
    onBack: () -> Unit,
    onOpenArticle: () -> Unit,
    summaryState: SummaryState = SummaryState.Idle,
    onSummarize: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(HeaderFraction)
            ) {
                val imageUrl = article.imageUrl
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        imageLoader = LocalImageLoader.current,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.15f),
                                0.7f to background.copy(alpha = 0.55f),
                                1f to background,
                            )
                        )
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (article.extract.isNotEmpty()) {
                    Text(
                        text = article.extract,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    )
                }
                if (article.categories.isNotEmpty()) {
                    Text(
                        text = article.categories.joinToString(" / "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    )
                }
                SummarySection(
                    state = summaryState,
                    onSummarize = onSummarize,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenArticle) {
                    Text(text = "Read full article")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        TopAppBar(
            title = {},
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

/** Fraction of the viewport the header image occupies before the text starts. */
private const val HeaderFraction = 0.4f
