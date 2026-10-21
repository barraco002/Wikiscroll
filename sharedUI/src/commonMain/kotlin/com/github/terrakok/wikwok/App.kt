package com.github.terrakok.wikwok

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalPlatformContext
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import com.github.terrakok.wikwok.data.InMemorySettings
import com.github.terrakok.wikwok.data.WikipediaRepositoryImpl
import com.github.terrakok.wikwok.theme.AppTheme
import com.github.terrakok.wikwok.ui.OnboardingScreen
import com.github.terrakok.wikwok.ui.WikiscrollScreen
import com.github.terrakok.wikwok.ui.WikiscrollViewModel

/**
 * Image loader shared by every card in the feed.
 *
 * Provided once for the whole app so the loader's memory cache survives a page change. A loader per
 * card would refetch the same thumbnails on every recomposition.
 */
internal val LocalImageLoader = compositionLocalOf<ImageLoader> { error("ImageLoader not provided") }

/**
 * Root of the application.
 *
 * Two destinations, chosen by whether the reader has stated what they want to read. The feed's state
 * holder is not built until the onboarding is done, which is the whole point of the gate: seeding a
 * profile that a first card was already chosen against would waste the seed. The nullable list is
 * the decision itself, so there is no second boolean to keep in sync with it.
 *
 * @param onThemeChanged invoked with the resolved dark-theme flag, wired by the platform entry point.
 */
@Composable
fun App(
    onThemeChanged: @Composable (isDark: Boolean) -> Unit = {},
) = AppTheme(onThemeChanged) {
    val context = LocalPlatformContext.current
    val imageLoader = remember(context) { ImageLoader(context) }
    val repository = remember { WikipediaRepositoryImpl() }
    var chosenTopics by remember {
        mutableStateOf(if (InMemorySettings.isOnboardingCompleted) emptyList() else null)
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        val topics = chosenTopics
        if (topics == null) {
            OnboardingScreen(
                onContinue = { selected ->
                    InMemorySettings.setOnboardingCompleted(true)
                    chosenTopics = selected
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val feedViewModel = viewModel(key = "wikiscroll-feed") {
                WikiscrollViewModel(repository = repository, initialTopics = topics)
            }
            WikiscrollScreen(
                viewModel = feedViewModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
