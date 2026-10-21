package com.github.terrakok.wikwok.domain

/**
 * A single thing the reader did to one article card.
 *
 * Events are deliberately dumb. They record what happened and on which article, and they never
 * decide how much that action is worth. Every threshold and every weight lives in
 * [RecommendationEngine], so the same recorded event can be replayed against a different policy
 * without touching the call site or the persistence schema.
 *
 * [categories] is the full category set of the article that was interacted with. An empty list is
 * legal, since some pages carry no categories, and the engine treats it as a no-op.
 */
sealed class InteractionEvent {

    /** Stable identifier of the article, formatted as `language:pageId`. */
    abstract val articleId: String

    /** Every category the article belongs to, in the order returned by the API. */
    abstract val categories: List<String>

    /**
     * The reader stayed on an article long enough for the engine to consider it relevant.
     *
     * @property durationMs time between the pager settling on the page and the pager leaving it.
     * Must be non-negative; the engine decides whether the value clears the interest threshold.
     * @property expectedDurationMs time this card needed, taken from [Article.expectedReadMs]. The
     *   event carries it because the engine never sees the article: ranking works on candidates, and
     *   an event that had to look the article up would not be replayable from a log. The default is
     *   the estimate for the shortest readable card, so a caller that does not know the length still
     *   produces a sane verdict instead of a division by zero.
     */
    data class DwellTime(
        override val articleId: String,
        override val categories: List<String>,
        val durationMs: Long,
        val expectedDurationMs: Long = Article.DEFAULT_EXPECTED_READ_MS,
    ) : InteractionEvent() {
        init {
            require(durationMs >= 0) { "durationMs must be non-negative, was $durationMs" }
            require(expectedDurationMs > 0) {
                "expectedDurationMs must be positive, was $expectedDurationMs"
            }
        }
    }

    /**
     * The reader left the article faster than the rejection threshold.
     *
     * The engine re-checks the duration against [expectedDurationMs], so a caller that misclassifies
     * a slow interaction cannot damage the interest model.
     */
    data class FastSwipe(
        override val articleId: String,
        override val categories: List<String>,
        val durationMs: Long,
        val expectedDurationMs: Long = Article.DEFAULT_EXPECTED_READ_MS,
    ) : InteractionEvent() {
        init {
            require(durationMs >= 0) { "durationMs must be non-negative, was $durationMs" }
            require(expectedDurationMs > 0) {
                "expectedDurationMs must be positive, was $expectedDurationMs"
            }
        }
    }

    /**
     * The reader tapped like. This is the only explicit positive signal and it is idempotent:
     * liking the same article twice must not be emitted twice by the presentation layer.
     */
    data class LikeAction(
        override val articleId: String,
        override val categories: List<String>,
    ) : InteractionEvent()

    /**
     * The reader opened the full article from the card.
     *
     * The strongest signal the app can observe: the summary was not enough and the reader left the
     * feed to keep reading. It outranks a like, which costs nothing, because this one costs
     * attention.
     */
    data class ReadMore(
        override val articleId: String,
        override val categories: List<String>,
    ) : InteractionEvent()
}
