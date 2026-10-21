package com.github.terrakok.wikwok.ui

import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.ReadingPolicy

/**
 * A closed reading interval: the article the reader was on and how long they stayed.
 *
 * @property articleId the card that was on screen.
 * @property durationMs measured time between arrival and departure.
 * @property expectedDurationMs time that card needed, from [Article.expectedReadMs]. It travels with
 *   the report because the verdict is relative: the same interval is a finished card or an early
 *   exit depending on what was on screen.
 */
data class DwellReport(
    val articleId: String,
    val durationMs: Long,
    val expectedDurationMs: Long = Article.DEFAULT_EXPECTED_READ_MS,
) {

    /** True when the stay is long enough to count as interest. */
    val isDwell: Boolean get() = ReadingPolicy.isDwell(durationMs, expectedDurationMs)

    /** True when the stay is short enough to count as rejection. */
    val isFastSwipe: Boolean get() = ReadingPolicy.isFastSwipe(durationMs, expectedDurationMs)

    /** True when the reader finished the card and stayed on it. */
    val isDeepRead: Boolean get() = ReadingPolicy.isDeepRead(durationMs, expectedDurationMs)
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
 * The session measures; it does not judge. Whether an interval is a dwell or a rejection depends on
 * the card, which the session never sees, so the classification lives on [DwellReport] where the
 * caller can attach the article's expectation.
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
