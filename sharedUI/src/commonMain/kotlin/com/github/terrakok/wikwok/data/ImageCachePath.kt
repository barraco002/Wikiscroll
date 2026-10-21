package com.github.terrakok.wikwok.data

/**
 * Directory the image disk cache is allowed to live in, or null when the platform has none.
 *
 * Coil requires an explicit directory for its disk cache and offers no cross-platform default, so
 * the path has to come from the platform. Null is a legal answer: a browser has no filesystem to
 * grow, and the caller then configures the loader without a disk cache instead of crashing.
 */
internal expect fun imageCachePath(): String?
