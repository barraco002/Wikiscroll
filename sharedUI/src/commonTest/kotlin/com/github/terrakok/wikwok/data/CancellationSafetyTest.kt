package com.github.terrakok.wikwok.data

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the difference between a cancelled request and a failed one.
 *
 * A coroutine that is cancelled while a request is in flight throws [CancellationException], and
 * `runCatching` alone would turn that into a failed [Result]. The caller then reports an error and
 * keeps writing to a scope that no longer exists. These tests pin the behaviour the data layer needs:
 * cancellation propagates, ordinary failures stay in the result.
 */
class CancellationSafetyTest {

    @Test
    fun cancellationPropagatesInsteadOfBecomingAFailure() {
        assertFailsWith<CancellationException> {
            catchingCancellation<Unit> { throw CancellationException("scope closed") }
        }
    }

    @Test
    fun ordinaryFailuresAreStillReturnedAsResults() {
        val result = catchingCancellation<Unit> { error("network down") }

        assertTrue(result.isFailure)
        assertEquals("network down", result.exceptionOrNull()?.message)
    }

    @Test
    fun successfulBlocksReturnTheirValue() {
        val result = catchingCancellation { 42 }

        assertEquals(42, result.getOrNull())
    }
}
