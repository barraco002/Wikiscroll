package com.github.terrakok.wikwok

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalPlatformContext
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import com.github.terrakok.wikwok.data.WikipediaRepositoryImpl
import com.github.terrakok.wikwok.theme.AppTheme
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
 * The composition does three things and nothing else: build the image loader, build the view model
 * against a real repository, and hand the feed its state holder. The upstream browser layout with
 * its navigation graph, toolbar and share plumbing was removed with the legacy service: the feed has
 * no second destination, so none of that scaffolding had a caller left.
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
    val feedViewModel = viewModel { WikiscrollViewModel(repository) }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        WikiscrollScreen(
            viewModel = feedViewModel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
