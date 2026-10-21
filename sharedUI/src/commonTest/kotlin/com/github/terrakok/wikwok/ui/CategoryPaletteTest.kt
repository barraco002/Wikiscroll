package com.github.terrakok.wikwok.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The fallback backdrop is chosen by keyword, so the interesting cases are the ones where a word
 * is contained in another word or appears with punctuation.
 */
class CategoryPaletteTest {

    @Test
    fun anUnmatchedCategoryFallsBackToNeutral() {
        assertEquals(categoryColors(emptyList()), categoryColors(listOf("Software")))
    }

    @Test
    fun warDoesNotMatchInsideSoftware() {
        assertNotEquals(categoryColors(listOf("Software")), categoryColors(listOf("World War II")))
    }

    @Test
    fun historyAndScienceGetDifferentBackdrops() {
        assertNotEquals(categoryColors(listOf("History")), categoryColors(listOf("Physics")))
    }

    @Test
    fun theMatchIgnoresCaseAndPunctuation() {
        assertEquals(categoryColors(listOf("history")), categoryColors(listOf("HISTORY, 20th century")))
    }
}