package com.github.terrakok.wikwok.ui

import com.github.terrakok.wikwok.domain.Article
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DwellSessionTest {

    private var now = 0L
    private val session = DwellSession { now }

    @Test
    fun measuresTheIntervalBetweenArrivalAndDeparture() {
        session.arrive("en:1")
        now = 9_000

        val report = session.leave()

        assertEquals(DwellReport("en:1", 9_000), report)
        assertEquals(
            Article.DEFAULT_EXPECTED_READ_MS,
            report!!.expectedDurationMs,
            "the session measures; it does not know the card",
        )
    }

    @Test
    fun theVerdictFollowsTheAttachedExpectation() {
        session.arrive("en:2")
        now = 9_000
        val measured = session.leave()!!

        val shortCard = measured.copy(expectedDurationMs = 8_000)
        assertTrue(shortCard.isDwell, "9 s finishes a card that needs 8 s")
        assertFalse(shortCard.isFastSwipe)

        val longCard = measured.copy(expectedDurationMs = 30_000)
        assertFalse(longCard.isDwell)
        assertTrue(longCard.isFastSwipe, "9 s is under half of the 30 s that card needs")
    }

    @Test
    fun aStayBetweenHalfAndTheFullTimeCarriesNoSignal() {
        session.arrive("en:3")
        now = 9_000

        val report = session.leave()!!.copy(expectedDurationMs = 18_000)

        assertFalse(report.isDwell)
        assertFalse(report.isFastSwipe)
    }

    @Test
    fun aSecondPassOverTheSameCardIsADeepRead() {
        session.arrive("en:4")
        now = 9_000

        assertTrue(session.leave()!!.copy(expectedDurationMs = 4_000).isDeepRead)
    }

    @Test
    fun reenteringTheSamePageDoesNotRestartTheClock() {
        session.arrive("en:5")
        now = 5_000
        session.arrive("en:5")
        now = 8_000

        assertEquals(8_000, session.leave()!!.durationMs, "the first arrival wins")
    }

    @Test
    fun leavingWithoutArrivingReportsNothing() {
        assertNull(session.leave())
        assertFalse(session.isMeasuring)
    }

    @Test
    fun closingTwiceReportsOnce() {
        session.arrive("en:6")
        now = 2_000

        assertEquals(2_000, session.leave()!!.durationMs)
        assertNull(session.leave(), "a closed interval cannot be closed again")
    }

    @Test
    fun aBackwardsClockIsClampedToZero() {
        now = 10_000
        session.arrive("en:7")
        now = 1_000

        assertEquals(0, session.leave()!!.durationMs)
    }

    @Test
    fun theOpenMeasurementIsInspectable() {
        assertEquals(null, session.measuringArticleId)
        session.arrive("en:8")
        assertEquals("en:8", session.measuringArticleId)
        session.leave()
        assertEquals(null, session.measuringArticleId)
    }
}
