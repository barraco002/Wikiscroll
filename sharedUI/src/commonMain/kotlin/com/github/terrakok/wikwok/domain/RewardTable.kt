package com.github.terrakok.wikwok.domain

import kotlin.math.abs
import kotlin.random.Random

/**
 * What the reader can win on a payout turn.
 *
 * The set is closed on purpose: rewards are cosmetic or behavioural, never content or currency,
 * and every branch has a visible effect the presentation layer can render.
 */
sealed class Reward {

    /** A collectible badge shown on the streak screen. */
    data object Badge : Reward()

    /** Protects a streak from one missed day. Consumed automatically. */
    data object StreakFreeze : Reward()

    /** Five consecutive articles from the reader's strongest category. */
    data object DeepDive : Reward()

    /** Unlocks one of the alternate colour themes. */
    data object ThemeUnlock : Reward()

    /** The rare branch: full confetti plus a badge that cannot be obtained any other way. */
    data object Jackpot : Reward()
}

/**
 * Variable-ratio reward schedule.
 *
 * The ratio is fixed by the caller, every tenth article, while the payout is drawn from a weighted
 * table. A predictable interval with an unpredictable outcome is the schedule that produces the
 * strongest persistence of behaviour, and it is the one documented in the project README.
 *
 * The table is injectable so tests can pin a distribution and so the product can retune weights
 * without touching the draw algorithm.
 *
 * @param random source of entropy; inject a seeded [Random] to make a test deterministic.
 * @param entries weighted outcomes. Weights must be positive and sum to 1.
 */
class RewardTable(
    private val random: Random = Random.Default,
    private val entries: List<Entry> = DEFAULT_ENTRIES,
) {

    /**
     * One row of the payout table.
     *
     * @property reward outcome produced when this row is drawn.
     * @property weight probability of the row, expressed as a fraction of one.
     */
    data class Entry(val reward: Reward, val weight: Double)

    init {
        require(entries.isNotEmpty()) { "reward table must not be empty" }
        require(entries.all { it.weight > 0.0 }) { "every weight must be strictly positive" }
        val total = entries.sumOf { it.weight }
        require(abs(total - 1.0) <= WEIGHT_TOLERANCE) {
            "weights must sum to 1.0, was $total"
        }
    }

    /**
     * Draws one outcome.
     *
     * The last row is returned when floating-point accumulation leaves the roll fractionally
     * above the cumulative sum, which makes the function total rather than throwing.
     */
    fun draw(): Reward {
        val roll = random.nextDouble()
        var cumulative = 0.0
        for (entry in entries) {
            cumulative += entry.weight
            if (roll < cumulative) return entry.reward
        }
        return entries.last().reward
    }

    companion object {
        /** Tolerance for the weight sum check, covering binary floating-point drift. */
        const val WEIGHT_TOLERANCE: Double = 1e-6

        /** Probability of a cosmetic badge. */
        const val BADGE_WEIGHT: Double = 0.55

        /** Probability of a streak freeze. */
        const val STREAK_FREEZE_WEIGHT: Double = 0.20

        /** Probability of a deep dive. */
        const val DEEP_DIVE_WEIGHT: Double = 0.15

        /** Probability of a theme unlock. */
        const val THEME_UNLOCK_WEIGHT: Double = 0.09

        /** Probability of the jackpot. */
        const val JACKPOT_WEIGHT: Double = 0.01

        /** Production table: 55 / 20 / 15 / 9 / 1, as documented in the README. */
        val DEFAULT_ENTRIES: List<Entry> = listOf(
            Entry(Reward.Badge, BADGE_WEIGHT),
            Entry(Reward.StreakFreeze, STREAK_FREEZE_WEIGHT),
            Entry(Reward.DeepDive, DEEP_DIVE_WEIGHT),
            Entry(Reward.ThemeUnlock, THEME_UNLOCK_WEIGHT),
            Entry(Reward.Jackpot, JACKPOT_WEIGHT),
        )
    }
}
