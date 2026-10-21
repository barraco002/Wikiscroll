package com.github.terrakok.wikwok.domain

/**
 * What one session did, counted on the device.
 *
 * The feed claims to hold attention, and a claim needs a number behind it. These are the numbers.
 * They are collected from the same events the engine scores, so the panel that renders them cannot
 * disagree with the profile that acts on them: if a card counted as a fast swipe in the policy, it
 * counts as one here, because both read [ReadingPolicy].
 *
 * Three deliberate choices:
 *
 * - [articlesRead] counts *distinct* cards, not events. A reader who dwells on a card and then likes
 *   it interacted twice with one article, and reporting two would make the average meaningless.
 * - [totalDwellMs] includes the time spent on cards that were then rejected. Reading time is time
 *   the feed held the reader, and pretending a scanned card cost nothing would flatter the product.
 * - Nothing here is a percentage of a remote baseline. There is no baseline: the app makes no
 *   network call beyond Wikipedia itself, which is the reason the panel can exist at all.
 *
 * @property articlesRead cards the reader saw at least once this session.
 * @property totalDwellMs milliseconds with a card on screen.
 * @property likes explicit taps on the like control.
 * @property openMoreCount articles opened in full from a card.
 * @property fastSwipes cards abandoned before half of their expected reading time.
 */
data class SessionMetrics(
    val articlesRead: Int = 0,
    val totalDwellMs: Long = 0L,
    val likes: Int = 0,
    val openMoreCount: Int = 0,
    val fastSwipes: Int = 0,
) {

    /** Mean time per card, or zero before the first card. */
    val averageDwellMs: Long
        get() = if (articlesRead == 0) 0L else totalDwellMs / articlesRead

    /** Share of cards abandoned early, in the range 0.0 to 1.0. */
    val fastSwipeShare: Double
        get() = if (articlesRead == 0) 0.0 else fastSwipes.toDouble() / articlesRead

    /** True while nothing has been recorded yet. */
    val isEmpty: Boolean get() = articlesRead == 0

    /**
     * Returns these counters with one more event folded in.
     *
     * @param event the interaction the feed just recorded.
     * @param isFirstEventForArticle true only for the first event of a card, which is what keeps
     *   [articlesRead] a count of cards instead of a count of interactions.
     */
    internal fun recording(
        event: InteractionEvent,
        isFirstEventForArticle: Boolean,
    ): SessionMetrics {
        val read = if (isFirstEventForArticle) articlesRead + 1 else articlesRead
        return when (event) {
            is InteractionEvent.DwellTime -> copy(
                articlesRead = read,
                totalDwellMs = totalDwellMs + event.durationMs,
                fastSwipes = fastSwipes + if (event.isEarlyExit()) 1 else 0,
            )

            is InteractionEvent.FastSwipe -> copy(
                articlesRead = read,
                totalDwellMs = totalDwellMs + event.durationMs,
                fastSwipes = fastSwipes + if (event.isEarlyExit()) 1 else 0,
            )

            is InteractionEvent.LikeAction -> copy(articlesRead = read, likes = likes + 1)

            is InteractionEvent.ReadMore -> copy(articlesRead = read, openMoreCount = openMoreCount + 1)
        }
    }

    private fun InteractionEvent.isEarlyExit(): Boolean = when (this) {
        is InteractionEvent.DwellTime -> ReadingPolicy.isFastSwipe(durationMs, expectedDurationMs)
        is InteractionEvent.FastSwipe -> ReadingPolicy.isFastSwipe(durationMs, expectedDurationMs)
        else -> false
    }
}
