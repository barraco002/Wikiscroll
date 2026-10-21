package com.github.terrakok.wikwok.domain

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
 */
data class InterestState(
    val userInterestScores: Map<String, Int> = emptyMap(),
    val sessionHistory: List<String> = emptyList(),
    val seenCategories: Set<String> = emptySet(),
    val articlesRead: Int = 0,
    val streak: Streak = Streak(),
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
