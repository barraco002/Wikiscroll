package com.github.terrakok.wikwok.domain

import kotlin.random.Random

/**
 * The feed policy: turns interactions into interest scores and picks the next article.
 *
 * The engine is stateless. Every public function receives an [InterestState] and either returns a
 * new one or reads it, which makes the whole policy replayable from a recorded event log and
 * deterministic under a seeded [Random].
 *
 * Usage contract: call [nextArticle] to choose a card, render it, and call [processEvent] with the
 * interaction that card produced. The state returned by [processEvent] is the only state that
 * should be persisted; [nextArticle] never mutates anything by design, so an abandoned card cannot
 * pollute the profile.
 *
 * @param random entropy source. Inject a seeded instance to reproduce a feed exactly.
 */
class RecommendationEngine(
    private val random: Random = Random.Default,
) {

    /**
     * Seed reserved for the reward stream.
     *
     * Drawing it once during construction decouples payouts from card selection: a test can drive
     * one without perturbing the other, and [checkReward] stays idempotent for a given article
     * count.
     */
    private val rewardStreamSeed: Int = random.nextInt()

    /**
     * Applies [event] to [currentState] and returns the updated snapshot.
     *
     * Scoring rules, matching the project README:
     * - [InteractionEvent.DwellTime] with a duration of at least [MIN_DWELL_MS] adds
     *   [SCORE_DWELL_POINTS];
     * - [InteractionEvent.DwellTime] below [FAST_SWIPE_MS] scores nothing: a short stay is a
     *   rejection signal, but it is a different event from a reported swipe and is not punished
     *   twice;
     * - [InteractionEvent.FastSwipe] with a duration below [FAST_SWIPE_MS] subtracts
     *   [SCORE_FAST_SWIPE_POINTS];
     * - [InteractionEvent.LikeAction] adds [SCORE_LIKE_POINTS] and counts as a dwell.
     *
     * Out-of-range timings are not errors: they carry no scoring evidence, but the article is
     * still recorded as served so it cannot resurface later in the same session.
     *
     * Every scored event also feeds [InterestState.recentSignals], which is what shifts the
     * exploration band. An event that scores nothing contributes no signal either: a medium stay is
     * silence, not a vote.
     *
     * @param todayEpochDay current day as a Unix epoch day, supplied by the caller so the domain
     * layer stays free of clocks and time zones.
     * @return a new state with scores, history, seen categories, read counter, streak and signal
     * window updated.
     */
    fun processEvent(
        event: InteractionEvent,
        currentState: InterestState,
        todayEpochDay: Long,
    ): InterestState {
        val delta = when (event) {
            is InteractionEvent.DwellTime ->
                if (event.durationMs >= MIN_DWELL_MS) SCORE_DWELL_POINTS else 0

            is InteractionEvent.FastSwipe ->
                if (event.durationMs < FAST_SWIPE_MS) SCORE_FAST_SWIPE_POINTS else 0

            is InteractionEvent.LikeAction -> SCORE_LIKE_POINTS
        }

        val signal = when (event) {
            is InteractionEvent.DwellTime -> when {
                event.durationMs >= MIN_DWELL_MS -> ReadingSignal.Dwell
                event.durationMs < FAST_SWIPE_MS -> ReadingSignal.FastSwipe
                else -> null
            }

            is InteractionEvent.FastSwipe ->
                if (event.durationMs < FAST_SWIPE_MS) ReadingSignal.FastSwipe else null

            is InteractionEvent.LikeAction -> ReadingSignal.Dwell
        }

        return currentState
            .withScoreDelta(event.categories, delta)
            .withSignal(signal)
            .withServedArticle(event.articleId, event.categories, todayEpochDay)
    }

    /**
     * Chooses the next card from [candidates].
     *
     * The selection is a three-step cascade:
     *
     * 1. **Session filter.** Articles already served are removed. If that removes everything, the
     * original list is used instead of returning null, because repeating a card is a smaller
     * failure than emptying the feed.
     * 2. **Exploration.** One roll decides the arm, against [InterestState.explorationRate]. That
     * rate moves with the reader: a session where every card lands explores less than the neutral
     * split, a session where every card is rejected explores more. On the exploration arm,
     * candidates with at least one unexplored category are equally likely, which is the serendipity
     * path.
     * 3. **Exploitation (and the exploration fallback).** Candidates whose categories appear in
     * [InterestState.topCategories] are sampled proportionally to their strongest category score,
     * so a strong category dominates without monopolising the feed.
     *
     * If neither arm finds a candidate, a uniform pick from the filtered pool is returned.
     *
     * @return the chosen article, or null when [candidates] is empty.
     */
    fun nextArticle(candidates: List<Article>, currentState: InterestState): Article? {
        if (candidates.isEmpty()) return null

        val fresh = candidates.filterNot { currentState.isInSession(it.id) }
        val pool = fresh.ifEmpty { candidates }

        val exploring = random.nextDouble() < currentState.explorationRate

        if (exploring) {
            val unexplored = pool.filter { candidate ->
                candidate.categories.any { currentState.isUnexplored(it) }
            }
            if (unexplored.isNotEmpty()) {
                // Every unexplored category scores zero, so the weights are uniform by definition.
                return unexplored.weightedPick { UNEXPLORED_WEIGHT }
            }
        }

        val topCategories = currentState.topCategories().toSet()
        if (topCategories.isNotEmpty()) {
            val relevant = pool.filter { candidate ->
                candidate.categories.any { it in topCategories }
            }
            if (relevant.isNotEmpty()) {
                return relevant.weightedPick { candidate -> candidate.interestWeight(currentState) }
            }
        }

        return pool[random.nextInt(pool.size)]
    }

    /**
     * Returns the payout for the current article count, or null when no payout is due.
     *
     * A payout is due on every [REWARD_INTERVAL]-th article. The draw is seeded from the engine's
     * reward seed and [InterestState.articlesRead], so calling this twice for the same state
     * returns the same reward instead of paying out twice. The interval is fixed; only the outcome
     * is variable, which is the variable-ratio shape the product is built on.
     */
    fun checkReward(currentState: InterestState): Reward? {
        val read = currentState.articlesRead
        if (read <= 0 || read % REWARD_INTERVAL != 0) return null
        return RewardTable(Random(rewardStreamSeed * HASH_MULTIPLIER + read)).draw()
    }

    /**
     * Samples one element proportionally to [weight].
     *
     * Falls back to a uniform pick when every weight is zero, negative or non-finite, which keeps
     * the function total for malformed state.
     */
    private fun <T> List<T>.weightedPick(weight: (T) -> Double): T {
        val total = fold(0.0) { sum, item -> sum + weight(item) }
        if (total <= 0.0 || !total.isFinite()) return this[random.nextInt(size)]

        var roll = random.nextDouble() * total
        for (item in this) {
            roll -= weight(item)
            if (roll <= 0.0) return item
        }
        return last()
    }

    /** Strongest category score of this candidate, floored at one so weights stay positive. */
    private fun Article.interestWeight(state: InterestState): Double =
        categories.maxOfOrNull { state.scoreOf(it) }?.coerceAtLeast(1)?.toDouble() ?: 1.0

    companion object {
        /** Minimum dwell, in milliseconds, that counts as interest. */
        const val MIN_DWELL_MS: Long = 8_000L

        /** Maximum duration, in milliseconds, that counts as a rejection. */
        const val FAST_SWIPE_MS: Long = 1_500L

        /** Points added when the dwell threshold is cleared. */
        const val SCORE_DWELL_POINTS: Int = 2

        /** Points removed when the rejection threshold is met. */
        const val SCORE_FAST_SWIPE_POINTS: Int = -1

        /** Points added by an explicit like. */
        const val SCORE_LIKE_POINTS: Int = 5

        /** Exploration rate for a balanced or empty session window. */
        const val NEUTRAL_EXPLORATION_RATE: Double = 0.30

        /**
         * Exploration rate at the bottom of the band, reached by a reader who keeps reading.
         *
         * The floor exists so the feed never becomes a pure echo chamber: even a perfectly
         * satisfied reader is served novelty occasionally.
         */
        const val MIN_EXPLORATION_RATE: Double = 0.15

        /**
         * Exploration rate at the top of the band, reached by a reader who rejects everything.
         *
         * The ceiling exists so a bad streak cannot turn the feed into pure noise: most cards still
         * follow the profile.
         */
        const val MAX_EXPLORATION_RATE: Double = 0.40

        /** Documented neutral split, kept for callers that assume a fixed rate. */
        const val EXPLORATION_RATE: Double = NEUTRAL_EXPLORATION_RATE

        /** Weight used for unexplored candidates, uniform by construction. */
        const val UNEXPLORED_WEIGHT: Double = 1.0

        /** Articles served between two payout turns. */
        const val REWARD_INTERVAL: Int = 10

        /** Odd multiplier used to spread reward seeds across article counts. */
        const val HASH_MULTIPLIER: Int = 31
    }
}
