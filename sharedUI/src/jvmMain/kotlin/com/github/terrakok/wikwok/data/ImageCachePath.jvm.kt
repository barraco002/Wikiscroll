package com.github.terrakok.wikwok.data

import java.io.File

/**
 * Per-user cache directory next to the settings node.
 *
 * Desktop has no managed cache folder, so the path is derived from the home directory the same way
 * other JVM applications do. The directory is created on demand by the cache itself.
 */
internal actual fun imageCachePath(): String =
    File(File(System.getProperty("user.home"), ".wikiscroll"), CacheDirectoryName).absolutePath

private const val CacheDirectoryName = "image_cache"
