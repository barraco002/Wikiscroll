package com.github.terrakok.wikwok.domain

import kotlinx.serialization.Serializable

/**
 * A single observation about how the last card landed.
 *
 * Only two outcomes matter to the policy: the reader stayed, or the reader left immediately. A
 * medium stay carries no signal because it neither confirms nor rejects the topic.
 */
@Serializable
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
 * [MAX_SESSION_HISTORY] entries so a long session cannot grow without limit. The window outlives
 * the process, so a card the reader saw yesterday does not come back after a restart.
 * @property seenCategories every category ever served, used by the exploration arm.
 * @property articlesRead number of cards served, drives the variable-ratio reward schedule.
 * @property streak consecutive-day reading streak.
 * @property recentlyRejectedCategories categories the reader swiped past, mapped to the number of
 *   cards still to be served before the topic is allowed back. A category absent from the map is
 *   not punished; a value of zero has the same meaning.
 * @property recentSignals the last [SIGNAL_WINDOW] outcomes, oldest first. Drives
 * [explorationRate] and is deliberately separate from [sessionHistory]: the history answers what
 * was shown, this answers how it went.
 */
@Serializable
data class InterestState(
    val userInterestScores: Map<String, Int> = emptyMap(),
    val sessionHistory: List<String> = emptyList(),
    val seenCategories: Set<String> = emptySet(),
    val articlesRead: Int = 0,
    val streak: Streak = Streak(),
    val recentlyRejectedCategories: Map<String, Int> = emptyMap(),
    val recentSignals: List<ReadingSignal> = emptyList(),
) {

    /** True while [category] is still buried by the anti-boredom penalty. */
    fun isRejected(category: String): Boolean =
        (recentlyRejectedCategories[category] ?: 0) > 0

    /**
     * Advances the penalty clock by one served card.
     *
     * Every punished category loses one turn, which is what makes the block temporary instead of a
     * permanent ban. Entries that reach zero are dropped rather than kept at zero, so the map
     * cannot grow for the length of the session.
     */
    fun decayRejections(): InterestState {
        if (recentlyRejectedCategories.isEmpty()) return this

        val next = buildMap {
            for ((category, turns) in recentlyRejectedCategories) {
                if (turns > 1) put(category, turns - 1)
            }
        }
        return copy(recentlyRejectedCategories = next)
    }

    /** Current score for [category], or zero when the category was never seen. */
    fun scoreOf(category: String): Int = userInterestScores[category] ?: 0

    /**
     * Points earned by [categories], exact matches and seeded topics alike.
     *
     * Exact matches are the learned path: they are what swiping produces. Seeded topics are free
     * text chosen before the feed existed ("nordic mythology"), which never equals a Wikipedia
     * category ("Category:Norse mythology"), so a whole-word, case-insensitive containment pass runs
     * as well. Whole word matters: without it a seed for "art" would match "Category:Artificial
     * intelligence" and every other word that merely starts the same way.
     *
     * @param categories Wikipedia categories of one candidate article.
     */
    fun matchScore(categories: List<String>): Int {
        if (categories.isEmpty() || userInterestScores.isEmpty()) return 0
        var total = 0
        for (category in categories) {
            userInterestScores[category]?.let { total += it }
            for ((topic, score) in userInterestScores) {
                if (score <= 0 || topic == category) continue
                if (category.containsTopic(topic)) total += score
            }
        }
        return total
    }

    /**
     * True when [category] matches a positive interest, exactly or through a seeded topic.
     *
     * This is the exploitation test. It is deliberately looser than [scoreOf]: a category that the
     * reader asked for by name is relevant even though the engine never scored it directly.
     */
    fun isPreferred(category: String): Boolean =
        scoreOf(category) > 0 ||
            userInterestScores.any { (topic, score) -> score > 0 && topic != category && category.containsTopic(topic) }

    /**
     * True when [category] carries no evidence at all: no score and no recorded visit.
     *
     * Both conditions are required. A category with a positive score must never enter the
     * exploration arm just because a reconstructed state has not populated [seenCategories], and a
     * category that was served but scored zero is not a serendipity candidate either. A seeded topic
     * counts as evidence, so an article that answers a stated interest is never sold as serendipity.
     */
    fun isUnexplored(category: String): Boolean =
        !isPreferred(category) && category !in seenCategories

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
    /**
     * Adds [topics] as known interests worth [SEED_INTEREST_POINTS] each.
     *
     * Seeding uses the maximum rather than a sum, so calling it twice with the same topic cannot
     * inflate the weight past the seeded value, and a topic the reader already learned to like keeps
     * whatever higher score it earned.
     */
    internal fun seededWith(topics: List<String>): InterestState {
        if (topics.isEmpty()) return this
        val updated = userInterestScores.toMutableMap()
        for (topic in topics) {
            updated[topic] = maxOf(updated[topic] ?: 0, SEED_INTEREST_POINTS)
        }
        return copy(userInterestScores = updated)
    }

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

    /**
     * Returns the profile with the session-scoped bookkeeping cleared.
     *
     * Used when a stored profile is loaded. Penalties and the signal window describe the last few
     * minutes of a session that has already ended: carrying them into the next launch would open the
     * feed with a topic buried by a swipe the reader has long forgotten, and would start the
     * exploration band in whatever mood the previous session finished in. Everything that describes
     * the reader rather than the moment is kept.
     */
    internal fun withoutSessionState(): InterestState =
        copy(recentlyRejectedCategories = emptyMap(), recentSignals = emptyList())

    companion object {
        /** Hard cap on [sessionHistory], large enough to prevent repeats inside a session. */
        const val MAX_SESSION_HISTORY: Int = 200

        /** Default size of [topCategories], matching a screen of prefetched cards. */
        const val DEFAULT_TOP_LIMIT: Int = 5

        /** Number of recent outcomes the adaptive band looks at. */
        const val SIGNAL_WINDOW: Int = 10

        /**
         * Points given to a topic chosen in the onboarding.
         *
         * Five dwells' worth, which is enough to make the seeded topics the strongest signal in a
         * fresh profile without making them unbeatable once the reader starts swiping.
         */
        const val SEED_INTEREST_POINTS: Int = 10
    }
}

/**
 * True when this string contains [topic] as whole words, ignoring case.
 *
 * A plain "contains" would make a seed for "art" match "Artificial intelligence" and "Bartholomew".
 * Word boundaries are checked by hand rather than with a regular expression because this runs for
 * every category of every candidate and a compiled pattern per comparison is not worth it.
 */
private fun String.containsTopic(topic: String): Boolean {
    if (topic.isBlank()) return false
    var index = indexOf(topic, ignoreCase = true)
    while (index >= 0) {
        val before = if (index == 0) ' ' else this[index - 1]
        val afterIndex = index + topic.length
        val after = if (afterIndex >= length) ' ' else this[afterIndex]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
        index = indexOf(topic, index + 1, ignoreCase = true)
    }
    return false
}

/**
 * Consecutive-day reading streak.
 *
 * Stored as an epoch day rather than a timestamp so that daylight-saving transitions and time
 * zones cannot break the sequence.
 */
@Serializable
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
