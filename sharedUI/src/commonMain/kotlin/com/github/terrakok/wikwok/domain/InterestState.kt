package com.github.terrakok.wikwok.domain

/**
 * A single observation about how the last card landed.
 *
 * Only two outcomes matter to the policy: the reader stayed, or the reader left immediately. A
 * medium stay carries no signal because it neither confirms nor rejects the topic.
 */
enum class ReadingSignal {

    /** The reader stayed past the dwell threshold, or asked for the article explicitly. */
    Dwell,

    /** The reader moved on before the fast-swipe threshold. */
    FastSwipe,
}

/**
 * Immutable snapshot of everything the engine knows about one reader.
 *
 * The state is a value: every mutation returns a new instance, which keeps the engine
 * deterministic under test and makes persistence a serialisation problem rather than a
 * concurrency problem.
 *
 * @property userInterestScores accumulated points per category. Never negative.
 * @property sessionHistory ids of the articles already served, oldest first, bounded to
 * [MAX_SESSION_HISTORY] entries so a long session cannot grow without limit.
 * @property seenCategories every category ever served, used by the exploration arm.
 * @property articlesRead number of cards served, drives the variable-ratio reward schedule.
 * @property streak consecutive-day reading streak.
 * @property recentSignals the last [SIGNAL_WINDOW] outcomes, oldest first. Drives
 * [explorationRate] and is deliberately separate from [sessionHistory]: the history answers what
 * was shown, this answers how it went.
 */
data class InterestState(
    val userInterestScores: Map<String, Int> = emptyMap(),
    val sessionHistory: List<String> = emptyList(),
    val seenCategories: Set<String> = emptySet(),
    val articlesRead: Int = 0,
    val streak: Streak = Streak(),
    val recentSignals: List<ReadingSignal> = emptyList(),
) {

    /** Current score for [category], or zero when the category was never seen. */
    fun scoreOf(category: String): Int = userInterestScores[category] ?: 0

    /**
     * True when [category] carries no evidence at all: no score and no recorded visit.
     *
     * Both conditions are required. A category with a positive score must never enter the
     * exploration arm just because a reconstructed state has not populated [seenCategories], and a
     * category that was served but scored zero is not a serendipity candidate either.
     */
    fun isUnexplored(category: String): Boolean =
        scoreOf(category) == 0 && category !in seenCategories

    /** Categories with a positive score, highest first, at most [limit] entries. */
    fun topCategories(limit: Int = DEFAULT_TOP_LIMIT): List<String> =
        userInterestScores.entries
            .asSequence()
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
            .toList()

    /** True when the article was already served in this session and must not come back. */
    fun isInSession(articleId: String): Boolean = articleId in sessionHistory

    /** Dwell events inside the current window. */
    val recentDwells: Int get() = recentSignals.count { it == ReadingSignal.Dwell }

    /** Fast swipes inside the current window. */
    val recentFastSwipes: Int get() = recentSignals.count { it == ReadingSignal.FastSwipe }

    /**
     * Share of the next cards that should come from the exploration arm.
     *
     * The band is adaptive rather than fixed. A reader who is still reading is served what the
     * profile says they like, up to [RecommendationEngine.MIN_EXPLORATION_RATE] exploration; a
     * reader who is bouncing off everything is served novelty, up to
     * [RecommendationEngine.MAX_EXPLORATION_RATE]. The neutral point is the documented 70/30 split,
     * so an empty or balanced window reproduces the original policy exactly.
     *
     * Strain is measured against the full window rather than against the number of recorded
     * signals. Otherwise a single unlucky swipe would swing the arm by 23 points, which is a policy
     * change driven by noise. With the window as denominator, reaching either end of the band takes
     * a consistent pattern.
     */
    val explorationRate: Double
        get() {
            if (recentSignals.isEmpty()) return RecommendationEngine.NEUTRAL_EXPLORATION_RATE
            val strain = (recentFastSwipes - recentDwells).toDouble() / SIGNAL_WINDOW
            return if (strain >= 0.0) {
                RecommendationEngine.NEUTRAL_EXPLORATION_RATE +
                    strain * (RecommendationEngine.MAX_EXPLORATION_RATE - RecommendationEngine.NEUTRAL_EXPLORATION_RATE)
            } else {
                RecommendationEngine.NEUTRAL_EXPLORATION_RATE +
                    strain * (RecommendationEngine.NEUTRAL_EXPLORATION_RATE - RecommendationEngine.MIN_EXPLORATION_RATE)
            }
        }

    /**
     * Applies [delta] to every category in [categories], clamping individual scores at zero.
     *
     * The clamp is the reason a rejected category can recover: a negative score is never stored,
     * so one good article is enough to bring a category back into the exploitation pool.
     */
    internal fun withScoreDelta(categories: List<String>, delta: Int): InterestState {
        if (categories.isEmpty() || delta == 0) return this
        val updated = userInterestScores.toMutableMap()
        for (category in categories) {
            updated[category] = (scoreOf(category) + delta).coerceAtLeast(0)
        }
        return copy(userInterestScores = updated)
    }

    /**
     * Appends [signal] to the window, dropping the oldest entry when it is full.
     *
     * A null signal leaves the state untouched, which is how a medium stay is expressed: the card
     * still counts as served by [withServedArticle], but it says nothing about the reader.
     */
    internal fun withSignal(signal: ReadingSignal?): InterestState {
        if (signal == null) return this
        return copy(recentSignals = (recentSignals + signal).takeLast(SIGNAL_WINDOW))
    }

    /**
     * Records that [articleId] was just served.
     *
     * @param todayEpochDay current day as a Unix epoch day supplied by the caller, which keeps
     * this class free of any clock or time-zone dependency.
     */
    internal fun withServedArticle(
        articleId: String,
        categories: List<String>,
        todayEpochDay: Long,
    ): InterestState {
        val history = (sessionHistory + articleId).takeLast(MAX_SESSION_HISTORY)
        return copy(
            sessionHistory = history,
            seenCategories = seenCategories + categories,
            articlesRead = articlesRead + 1,
            streak = streak.visit(todayEpochDay),
        )
    }

    companion object {
        /** Hard cap on [sessionHistory], large enough to prevent repeats inside a session. */
        const val MAX_SESSION_HISTORY: Int = 200

        /** Default size of [topCategories], matching a screen of prefetched cards. */
        const val DEFAULT_TOP_LIMIT: Int = 5

        /** Number of recent outcomes the adaptive band looks at. */
        const val SIGNAL_WINDOW: Int = 10
    }
}

/**
 * Consecutive-day reading streak.
 *
 * Stored as an epoch day rather than a timestamp so that daylight-saving transitions and time
 * zones cannot break the sequence.
 */
data class Streak(
    val days: Int = 0,
    val lastVisitEpochDay: Long? = null,
) {

    /**
     * Returns the streak after a visit on [epochDay].
     *
     * - first visit ever: streak starts at one day;
     * - same day: unchanged;
     * - next day: incremented;
     * - a gap of two days or more: restarts at one;
     * - a clock that moved backwards: unchanged.
     */
    fun visit(epochDay: Long): Streak {
        val previous = lastVisitEpochDay ?: return Streak(days = 1, lastVisitEpochDay = epochDay)
        return when {
            epochDay == previous -> this
            epochDay == previous + 1 -> copy(days = days + 1, lastVisitEpochDay = epochDay)
            epochDay > previous -> Streak(days = 1, lastVisitEpochDay = epochDay)
            else -> this
        }
    }
}
