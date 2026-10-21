package com.github.terrakok.wikwok.domain

/**
 * Turns a stopwatch reading into a verdict about one card.
 *
 * The thresholds are relative, not absolute. A fixed eight seconds is the wrong yardstick for the
 * same reason a fixed word count is the wrong yardstick for a chapter: a short card read to the end
 * and a long card abandoned halfway are not the same behaviour, but they produce the same number.
 * Every comparison here is made against [Article.expectedReadMs], so the verdict follows the content.
 *
 * The three bands are deliberately asymmetric:
 *
 * - a stay that reaches the expected time is a **dwell**, the card was finished;
 * - a stay of at least half that time is a **medium stay** and carries no signal, because leaving
 *   mid-paragraph says nothing about the topic;
 * - a stay under half the expected time is a **fast swipe**, the reader decided before reading.
 *
 * The deep-read band exists because the policy should reward a second pass over a card, and the
 * engine scores it one point higher than a plain dwell.
 */
object ReadingPolicy {

    /** Share of the expected reading time below which a stay is a rejection. */
    const val FAST_SWIPE_FRACTION: Double = 0.5

    /** Multiple of the expected reading time at which a stay counts as a second read. */
    const val DEEP_READ_FACTOR: Double = 2.0

    /**
     * Duration, in milliseconds, below which a stay on a card of [expectedDurationMs] is a rejection.
     *
     * A non-positive expectation is treated as one millisecond rather than throwing: the value is
     * derived from content length, and a malformed event must not crash the feed.
     */
    fun fastSwipeThresholdMs(expectedDurationMs: Long): Long =
        (expectedDurationMs.coerceAtLeast(1L) * FAST_SWIPE_FRACTION).toLong()

    /** True when a stay of [durationMs] reaches the time the card needs. */
    fun isDwell(durationMs: Long, expectedDurationMs: Long): Boolean =
        durationMs >= expectedDurationMs.coerceAtLeast(1L)

    /** True when a stay of [durationMs] is long enough to be a second pass over the card. */
    fun isDeepRead(durationMs: Long, expectedDurationMs: Long): Boolean =
        durationMs >= (expectedDurationMs.coerceAtLeast(1L) * DEEP_READ_FACTOR).toLong()

    /** True when a stay of [durationMs] is short enough to read as a rejection. */
    fun isFastSwipe(durationMs: Long, expectedDurationMs: Long): Boolean =
        durationMs < fastSwipeThresholdMs(expectedDurationMs)
}
