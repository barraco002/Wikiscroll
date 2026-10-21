package com.github.terrakok.wikwok.ui

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
        assertTrue(report!!.isDwell)
        assertFalse(report.isFastSwipe)
    }

    @Test
    fun aSwipeUnderTheThresholdIsAFastSwipe() {
        session.arrive("en:2")
        now = 400

        val report = session.leave()!!

        assertTrue(report.isFastSwipe)
        assertFalse(report.isDwell)
    }

    @Test
    fun aMediumStayIsNeitherDwellNorFastSwipe() {
        session.arrive("en:3")
        now = 4_000

        val report = session.leave()!!

        assertFalse(report.isDwell)
        assertFalse(report.isFastSwipe)
    }

    @Test
    fun reenteringTheSamePageDoesNotRestartTheClock() {
        session.arrive("en:4")
        now = 5_000
        session.arrive("en:4")
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
        session.arrive("en:5")
        now = 2_000

        assertEquals(2_000, session.leave()!!.durationMs)
        assertNull(session.leave(), "a closed interval cannot be closed again")
    }

    @Test
    fun aBackwardsClockIsClampedToZero() {
        now = 10_000
        session.arrive("en:6")
        now = 1_000

        assertEquals(0, session.leave()!!.durationMs)
    }

    @Test
    fun theOpenMeasurementIsInspectable() {
        assertEquals(null, session.measuringArticleId)
        session.arrive("en:7")
        assertEquals("en:7", session.measuringArticleId)
        session.leave()
        assertEquals(null, session.measuringArticleId)
    }
}
