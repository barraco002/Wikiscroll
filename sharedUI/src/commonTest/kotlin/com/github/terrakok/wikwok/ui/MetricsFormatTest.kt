package com.github.terrakok.wikwok.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsFormatTest {

    @Test
    fun formatsMinutesAndPaddedSeconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_400))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("12:00", formatDuration(720_000))
    }

    @Test
    fun aNegativeIntervalCannotProduceANegativeReading() {
        assertEquals("0:00", formatDuration(-5_000))
    }

    @Test
    fun theFastSwipeShareIsTruncatedNotRoundedUp() {
        assertEquals("4 (18%)", formatFastSwipes(4, 0.1875))
        assertEquals("1 (50%)", formatFastSwipes(1, 0.5))
    }

    @Test
    fun noFastSwipesIsJustAZero() {
        assertEquals("0", formatFastSwipes(0, 0.0))
    }
}
