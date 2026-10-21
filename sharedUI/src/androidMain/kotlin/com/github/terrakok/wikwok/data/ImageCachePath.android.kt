package com.github.terrakok.wikwok.data

import java.io.File

/**
 * Cache directory owned by the application.
 *
 * The system may clear it under storage pressure, which is exactly the contract an image cache
 * wants: everything inside is reproducible from the network.
 *
 * The return type repeats the nullable one from the expect declaration verbatim. An actual
 * declaration has to match the expected signature exactly, so returning the non-null type here is
 * a compile error even though every Android path does produce a directory.
 */
internal actual fun imageCachePath(): String? =
    File(AppContextHolder.requireContext().cacheDir, CacheDirectoryName).absolutePath

private const val CacheDirectoryName = "image_cache"
