package com.github.terrakok.wikwok.ui

import com.github.terrakok.wikwok.domain.RecommendationEngine

/** A closed reading interval: the article the reader was on and how long they stayed. */
data class DwellReport(val articleId: String, val durationMs: Long) {

    /** True when the stay is long enough to count as interest. */
    val isDwell: Boolean get() = durationMs >= RecommendationEngine.MIN_DWELL_MS

    /** True when the stay is short enough to count as rejection. */
    val isFastSwipe: Boolean get() = durationMs < RecommendationEngine.FAST_SWIPE_MS
}

/**
 * Measures how long the reader stays on each article.
 *
 * Kept out of the composable on purpose: the timing rules are the product decision, and they are
 * easier to reason about, and to test, as plain arithmetic on a clock.
 *
 * The session is a small state machine with two states. It is either measuring an article or it is
 * not. [arrive] opens a measurement, [leave] closes it and returns the interval. A second [arrive]
 * without an intervening [leave] is ignored, so two snapshots of the same settled page cannot
 * restart the clock and erase a long read.
 *
 * @param clock monotonic-enough millisecond source. Injected so a test can move time by hand.
 */
class DwellSession(private val clock: () -> Long) {

    private var openArticleId: String? = null
    private var startedAt: Long = 0

    /** True while an article is being measured. */
    val isMeasuring: Boolean get() = openArticleId != null

    /** The id currently being measured, or null when the session is closed. */
    val measuringArticleId: String? get() = openArticleId

    /**
     * Starts measuring [id].
     *
     * Starting a measurement that is already running is a no-op: the original start time wins.
     */
    fun arrive(id: String) {
        if (openArticleId != null) return
        openArticleId = id
        startedAt = clock()
    }

    /**
     * Closes the current measurement.
     *
     * A negative interval cannot occur with a sane clock, but it is clamped anyway so a wall-clock
     * jump backwards cannot produce an event with negative duration, which the domain rejects.
     *
     * @return the interval, or null when nothing was being measured.
     */
    fun leave(): DwellReport? {
        val id = openArticleId ?: return null
        val elapsed = (clock() - startedAt).coerceAtLeast(0)
        openArticleId = null
        return DwellReport(id, elapsed)
    }
}
