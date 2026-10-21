package com.github.terrakok.wikwok.data

import java.io.File

/**
 * Cache directory owned by the application.
 *
 * The system may clear it under storage pressure, which is exactly the contract an image cache
 * wants: everything inside is reproducible from the network.
 */
internal actual fun imageCachePath(): String =
    File(AppContextHolder.requireContext().cacheDir, CacheDirectoryName).absolutePath

private const val CacheDirectoryName = "image_cache"
