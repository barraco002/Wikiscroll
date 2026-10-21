package com.github.terrakok.wikwok.data

/**
 * No disk cache on iOS yet.
 *
 * Resolving the caches directory needs Foundation and there is no iOS job in the pipeline, so the
 * path is left null rather than shipping a call nothing ever compiles. The loader then runs with the
 * memory cache only, which is correct but costs bandwidth on a cold start.
 */
internal actual fun imageCachePath(): String? = null
