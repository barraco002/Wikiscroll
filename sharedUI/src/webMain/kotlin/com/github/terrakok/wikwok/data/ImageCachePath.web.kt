package com.github.terrakok.wikwok.data

/**
 * No disk cache in the browser.
 *
 * The HTTP layer already keeps its own cache and the app has no filesystem to own. Returning null
 * keeps the loader configuration identical across platforms.
 */
internal actual fun imageCachePath(): String? = null
