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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.github.terrakok.wikwok.data.PlatformSettingsStorage
import com.github.terrakok.wikwok.data.ProfileStore
import com.github.terrakok.wikwok.data.SettingsStorage
import com.github.terrakok.wikwok.data.imageCachePath
import com.github.terrakok.wikwok.data.WikipediaRepositoryImpl
import com.github.terrakok.wikwok.theme.AppTheme
import com.github.terrakok.wikwok.ui.OnboardingScreen
import com.github.terrakok.wikwok.ui.WikiscrollScreen
import com.github.terrakok.wikwok.ui.WikiscrollViewModel
import okio.Path.Companion.toPath

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
 * Two destinations, chosen by whether the reader has stated what they want to read. The answer is
 * persisted by [PlatformSettingsStorage], so the onboarding runs once per install rather than once
 * per launch. The feed's state holder is not built until the onboarding is done, which is the whole
 * point of the gate: seeding a profile that a first card was already chosen against would waste the
 * seed. The nullable list is the decision itself, so there is no second boolean to keep in sync.
 *
 * The same store also holds the learned profile, through [ProfileStore]. Both live here rather than
 * inside the view model so the state holder receives a dependency it can be tested without, and so
 * one store instance serves the flag and the profile instead of two objects owning one file.
 *
 * @param onThemeChanged invoked with the resolved dark-theme flag, wired by the platform entry point.
 */
@Composable
fun App(
    onThemeChanged: @Composable (isDark: Boolean) -> Unit = {},
) = AppTheme(onThemeChanged) {
    val context = LocalPlatformContext.current
    val imageLoader = remember(context) { createImageLoader(context) }
    val repository = remember { WikipediaRepositoryImpl() }
    val settings: SettingsStorage = remember { PlatformSettingsStorage() }
    val profileStore = remember(settings) { ProfileStore(settings) }
    var chosenTopics by remember {
        mutableStateOf<List<String>?>(if (settings.isOnboardingCompleted) emptyList() else null)
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        val topics = chosenTopics
        if (topics == null) {
            OnboardingScreen(
                onContinue = { selected ->
                    settings.isOnboardingCompleted = true
                    chosenTopics = selected
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val feedViewModel = viewModel(key = "wikiscroll-feed") {
                WikiscrollViewModel(
                    repository = repository,
                    initialTopics = topics,
                    profileStore = profileStore,
                )
            }
            WikiscrollScreen(
                viewModel = feedViewModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Builds the image loader with bounded caches.
 *
 * An unbounded Coil loader keeps every decoded bitmap it has ever seen, and an infinite feed will
 * eventually hand it enough thumbnails to exhaust the heap. The memory cache is capped at a quarter
 * of the process heap, which leaves room for the pager's own bitmaps and the rest of the app; the
 * disk cache is capped at a fixed size so a long session cannot grow without limit.
 *
 * The disk cache is skipped entirely where the platform reports no directory, which is the browser
 * and, for now, iOS. Skipping it is not a broken state: the loader still works, it just refetches
 * over the network after a restart.
 *
 * @param context platform context the loader resolves dependencies from.
 */
private fun createImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, MemoryCacheFraction)
                .build()
        }
        .apply {
            val directory = imageCachePath()
            if (directory != null) {
                diskCache {
                    DiskCache.Builder()
                        .directory(directory.toPath())
                        .maxSizeBytes(DiskCacheBytes)
                        .build()
                }
            }
        }
        .build()

/** Share of the process heap the decoded-bitmap cache may occupy. */
private const val MemoryCacheFraction = 0.25

/** Hard ceiling for the on-disk image cache, in bytes. */
private const val DiskCacheBytes = 100L * 1024 * 1024
