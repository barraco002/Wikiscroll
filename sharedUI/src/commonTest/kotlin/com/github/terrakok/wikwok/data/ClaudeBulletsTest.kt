package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.data.claude.BULLET_COUNT
import com.github.terrakok.wikwok.data.claude.parseSummaryBullets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parser is the part of the feature that fails silently: a model that answers with a numbered
 * list, or with a preamble, still has to produce three readable lines. Every case below is a shape a
 * language model actually returns.
 */
class ClaudeBulletsTest {

    @Test
    fun hyphenatedLinesBecomeBullets() {
        val text = "- The first\n- The second\n- The third"

        assertEquals(listOf("The first", "The second", "The third"), parseSummaryBullets(text))
    }

    @Test
    fun numberedLinesAreStrippedToo() {
        val text = "1. One\n2. Two\n3) Three"

        assertEquals(listOf("One", "Two", "Three"), parseSummaryBullets(text))
    }

    @Test
    fun asteriskAndBulletCharactersAreStripped() {
        val text = "* Starred\n+ Plussed\n\u2022 Dotted"

        assertEquals(listOf("Starred", "Plussed", "Dotted"), parseSummaryBullets(text))
    }

    @Test
    fun aPreambleBeforeTheListIsDropped() {
        val text = "Here are three points:\n- Alpha\n- Beta\n- Gamma"

        assertEquals(listOf("Alpha", "Beta", "Gamma"), parseSummaryBullets(text))
    }

    @Test
    fun anAnswerWithoutMarkersFallsBackToItsFirstLines() {
        val text = "Alpha\nBeta\nGamma\nDelta"

        assertEquals(listOf("Alpha", "Beta", "Gamma"), parseSummaryBullets(text))
    }

    @Test
    fun moreThanThreeAReTruncated() {
        val text = (1..6).joinToString("\n") { "- Line " + it }

        assertEquals(BULLET_COUNT, parseSummaryBullets(text).size)
    }

    @Test
    fun blankTextProducesNothing() {
        assertTrue(parseSummaryBullets("   \n\n  ").isEmpty())
    }
}
