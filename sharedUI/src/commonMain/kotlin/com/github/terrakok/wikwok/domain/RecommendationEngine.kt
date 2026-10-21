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
     * Scoring rules, matching the project README. Every duration is compared against the time the
     * card needed (see [InteractionEvent.DwellTime.expectedDurationMs] and [ReadingPolicy]), never
     * against a fixed number of seconds:
     * - a stay that reaches the expected reading time adds [SCORE_DWELL_POINTS];
     * - a stay that reaches twice the expected reading time adds [SCORE_DEEP_READ_POINTS], because
     *   finishing a card and then reading it again is a stronger statement than finishing it;
     * - a stay between half and the full expected time scores nothing: the reader left mid-card, and
     *   that says nothing about the topic;
     * - a stay below half the expected time is a rejection signal but scores nothing as a
     *   [InteractionEvent.DwellTime]: it is a different event from a reported swipe and is not
     *   punished twice;
     * - [InteractionEvent.FastSwipe] below half the expected time subtracts
     *   [SCORE_FAST_SWIPE_POINTS];
     * - [InteractionEvent.LikeAction] adds [SCORE_LIKE_POINTS] and counts as a dwell.
     * - [InteractionEvent.ReadMore] adds [SCORE_READ_MORE_POINTS] and counts as a dwell. It costs
     *   the reader attention instead of a tap, so it outranks a like.
     *
     * A fast swipe does more than score: it buries its categories for [REJECTION_TURNS] cards, so
     * the next few picks are forced elsewhere. Every event also decays the existing penalties by
     * one turn, which is what makes the block temporary.
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
            is InteractionEvent.DwellTime -> when {
                ReadingPolicy.isDeepRead(event.durationMs, event.expectedDurationMs) ->
                    SCORE_DEEP_READ_POINTS

                ReadingPolicy.isDwell(event.durationMs, event.expectedDurationMs) ->
                    SCORE_DWELL_POINTS

                else -> 0
            }

            is InteractionEvent.FastSwipe ->
                if (ReadingPolicy.isFastSwipe(event.durationMs, event.expectedDurationMs)) {
                    SCORE_FAST_SWIPE_POINTS
                } else {
                    0
                }

            is InteractionEvent.LikeAction -> SCORE_LIKE_POINTS

            is InteractionEvent.ReadMore -> SCORE_READ_MORE_POINTS
        }

        val signal = when (event) {
            is InteractionEvent.DwellTime -> when {
                ReadingPolicy.isDwell(event.durationMs, event.expectedDurationMs) ->
                    ReadingSignal.Dwell

                ReadingPolicy.isFastSwipe(event.durationMs, event.expectedDurationMs) ->
                    ReadingSignal.FastSwipe

                else -> null
            }

            is InteractionEvent.FastSwipe ->
                if (ReadingPolicy.isFastSwipe(event.durationMs, event.expectedDurationMs)) {
                    ReadingSignal.FastSwipe
                } else {
                    null
                }

            is InteractionEvent.LikeAction -> ReadingSignal.Dwell

            is InteractionEvent.ReadMore -> ReadingSignal.Dwell
        }

        val buried = if (event.isRejection()) event.categories.distinct() else emptyList()

        // The decay runs before the new penalty is written. Decaying afterwards would charge the
        // penalty for the very card that created it, and a five-card block would only ever buy
        // four.
        val decayed = currentState.decayRejections()
        val rejected = if (buried.isEmpty()) {
            decayed
        } else {
            val merged = decayed.recentlyRejectedCategories.toMutableMap()
            for (category in buried) {
                merged[category] = REJECTION_TURNS
            }
            decayed.copy(recentlyRejectedCategories = merged)
        }

        return rejected
            .withScoreDelta(event.categories, delta)
            .withSignal(signal)
            .withServedArticle(event.articleId, event.categories, todayEpochDay)
    }

    /**
     * True when the event is a rejection strong enough to bury its categories.
     *
     * The duration is re-checked here rather than trusted, so a caller that mislabels a slow swipe
     * cannot blacklist a topic.
     */
    private fun InteractionEvent.isRejection(): Boolean =
        this is InteractionEvent.FastSwipe &&
            ReadingPolicy.isFastSwipe(durationMs, expectedDurationMs)

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
     * 3. **Exploitation (and the exploration fallback).** Candidates with at least one preferred
     * category are sampled proportionally to [InterestState.matchScore], so a strong category
     * dominates without monopolising the feed. Preferred means an exact hit or a seeded topic found
     * inside a category name, which is how free-text interests from the onboarding take effect.
     *
     * Before either arm runs, two gates remove candidates:
     *
     * 1. **Quality.** An article is dropped when its extract is shorter than [MIN_EXTRACT_LENGTH]
     *    or it carries fewer than [MIN_CATEGORIES] categories. A stub cannot tell the reader
     *    whether the topic is boring or the article is empty, so it is never shown. The ungated
     *    pool is restored when the gate would empty the feed.
     * 2. **Anti-boredom.** A candidate sitting in a rejected category is dropped until its penalty
     *    expires. Same restore rule: an empty feed is a worse outcome than a repeated topic.
     *
     * Both arms sample with [retentionWeight], the product of the interest affinity, the narrative
     * pathing bonus and the authority multiplier. If neither arm finds a candidate, the filtered
     * pool is sampled the same way.
     *
     * @param candidates the neighbourhood to pick from.
     * @param currentState everything the policy has learned so far.
     * @param currentArticle the card being left, used for narrative pathing. Null disables the
     *   pathing multipliers and leaves affinity and authority in charge.
     * @return the chosen article, or null when [candidates] is empty.
     */
    fun nextArticle(
        candidates: List<Article>,
        currentState: InterestState,
        currentArticle: Article? = null,
    ): Article? {
        if (candidates.isEmpty()) return null

        val fresh = candidates.filterNot { currentState.isInSession(it.id) }
        val sessionPool = fresh.ifEmpty { candidates }

        // Quality gate. A three-word extract is a stub, not a card: the reader cannot tell whether
        // the topic is boring or the article is empty, so the only safe ranking is to drop it.
        val readable = sessionPool.filter { candidate -> candidate.isFeedWorthy() }
        val qualityPool = readable.ifEmpty { sessionPool }

        // Anti-boredom gate. A topic the reader swiped past is dead for a few cards.
        val allowed = qualityPool.filterNot { candidate ->
            candidate.categories.any { category -> currentState.isRejected(category) }
        }

        // Falling back to the buried pool beats returning null: null sends the caller to the
        // network for a replacement, which is a slower card, not a more varied one.
        val pool = allowed.ifEmpty { qualityPool }
        val currentCategories = currentArticle?.categories?.toSet().orEmpty()

        val exploring = random.nextDouble() < currentState.explorationRate

        if (exploring) {
            val unexplored = pool.filter { candidate ->
                candidate.categories.any { currentState.isUnexplored(it) }
            }
            if (unexplored.isNotEmpty()) {
                return unexplored.weightedPick { candidate ->
                    candidate.retentionWeight(currentState, currentCategories)
                }
            }
        }

        if (currentState.userInterestScores.isNotEmpty()) {
            val relevant = pool.filter { candidate ->
                candidate.categories.any { currentState.isPreferred(it) }
            }
            if (relevant.isNotEmpty()) {
                return relevant.weightedPick { candidate ->
                    candidate.retentionWeight(currentState, currentCategories)
                }
            }
        }

        return pool.weightedPick { candidate ->
            candidate.retentionWeight(currentState, currentCategories)
        }
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

    /**
     * True when the article carries enough substance to hold a full-screen card.
     *
     * Both limits are deliberately low. They are not a quality ideal, they are the line below
     * which a card is provably empty: an extract too short to read, and a page with no category to
     * route from.
     */
    private fun Article.isFeedWorthy(): Boolean =
        extract.length >= MIN_EXTRACT_LENGTH && categories.size >= MIN_CATEGORIES

    /**
     * Relative priority of one candidate under the retention policy.
     *
     * Three independent opinions, multiplied:
     *
     * - **Affinity** from [InterestState.matchScore], normalised into 1.0 to 2.0 so a heavily
     *   liked topic cannot drown the other two signals. Without the normalisation, ten likes on one
     *   category would outrank every narrative edge on the board.
     * - **Narrative pathing.** Two shared categories is a story the reader is already inside
     *   ("World War II" to "Adolf Hitler" to "Nazi Germany"), worth [NARRATIVE_BONUS]. No shared
     *   category is a topic change, and is charged [NARRATIVE_PENALTY] for it.
     * - **Authority.** A landmark article is a better next step than an obscure neighbour even
     *   when both match, worth [AUTHORITY_BONUS].
     *
     * @param currentCategories categories of the card being left, empty when there is no origin.
     */
    private fun Article.retentionWeight(
        state: InterestState,
        currentCategories: Set<String>,
    ): Double {
        var weight = BASE_WEIGHT

        val affinity = state.matchScore(categories)
        if (affinity > 0) {
            weight *= 1.0 + affinity / (affinity + AFFINITY_SCALE)
        }

        if (currentCategories.isNotEmpty()) {
            val shared = categories.count { category -> category in currentCategories }
            weight *= when {
                shared >= NARRATIVE_SHARED_CATEGORIES -> NARRATIVE_BONUS
                shared == 0 -> NARRATIVE_PENALTY
                else -> 1.0
            }
        }

        if (isPopular) {
            weight *= AUTHORITY_BONUS
        }

        if (!isPopular && imageUrl == null) {
            weight *= NO_IMAGE_PENALTY
        }

        return weight
    }

    companion object {
        /** Points added when a stay reaches the time the card needed. */
        const val SCORE_DWELL_POINTS: Int = 2

        /**
         * Points added when a stay reaches twice the time the card needed.
         *
         * One more than a plain dwell: the reader finished the card and stayed, which is the
         * strongest passive signal the feed can observe. Only a like beats it, and only by three.
         */
        const val SCORE_DEEP_READ_POINTS: Int = 3

        /** Points removed when the rejection threshold is met. */
        const val SCORE_FAST_SWIPE_POINTS: Int = -1

        /** Points added by an explicit like. */
        const val SCORE_LIKE_POINTS: Int = 5

        /**
         * Points awarded by [InteractionEvent.ReadMore].
         *
         * Twice a like. Tapping like costs nothing; opening the article costs a decision to leave
         * the feed, which is the strongest statement a reader can make about a topic without
         * typing.
         */
        const val SCORE_READ_MORE_POINTS: Int = 10

        /** Shortest extract, in characters, that still reads as an article instead of a stub. */
        const val MIN_EXTRACT_LENGTH: Int = 300

        /** Fewest categories a candidate needs for the narrative signal to mean anything. */
        const val MIN_CATEGORIES: Int = 2

        /** Cards a category stays buried after a rejection. */
        const val REJECTION_TURNS: Int = 5

        /** Weight every candidate starts from, before any multiplier applies. */
        const val BASE_WEIGHT: Double = 1.0

        /** Multiplier when a candidate continues the current article's topic. */
        const val NARRATIVE_BONUS: Double = 2.5

        /** Multiplier when a candidate changes topic entirely. */
        const val NARRATIVE_PENALTY: Double = 0.5

        /** Shared categories needed to count as the same narrative thread. */
        const val NARRATIVE_SHARED_CATEGORIES: Int = 2

        /** Multiplier for an article that appears in the most viewed ranking. */
        const val AUTHORITY_BONUS: Double = 2.0

        /**
         * Multiplier for a card that would render without a lead image.
         *
         * Not a veto: a well-matched article with no picture still beats a poor match that has one.
         * The effect is a tie-breaker, which is all a visual signal should be worth.
         */
        const val NO_IMAGE_PENALTY: Double = 0.8

        /**
         * Affinity score at which the interest multiplier reaches 1.5.
         *
         * Chosen so the whole affinity term spans 1.0 to 2.0: the same order as [AUTHORITY_BONUS],
         * which is what keeps the three multipliers negotiating instead of one of them deciding.
         */
        const val AFFINITY_SCALE: Double = 20.0

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
